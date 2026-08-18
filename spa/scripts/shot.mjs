/**
 * Screenshot the running SPA so UI changes can be inspected instead of assumed.
 *
 * The dev server must already be up (`npm run dev`), and the BFF with it, or every pane renders
 * its error state. Output lands in spa/.shots/ which is git-ignored — screenshots of borrowed
 * projects' data must never be committed.
 *
 *   node scripts/shot.mjs                        both themes, desktop + narrow
 *   node scripts/shot.mjs --theme dark           one theme
 *   node scripts/shot.mjs --width 1600 --tag foo
 *
 * Console errors and failed requests are reported, since a blank pane usually means one of those
 * rather than a styling problem.
 */
import { chromium } from 'playwright'
import { mkdir, readdir } from 'node:fs/promises'
import path from 'node:path'

const args = process.argv.slice(2)
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const url = opt('url', 'http://localhost:5173')
const outDir = path.resolve('.shots')
const tag = opt('tag', '')
const themes = opt('theme') ? [opt('theme')] : ['light', 'dark']
const widths = opt('width') ? [Number(opt('width'))] : [1440, 900]
const settle = Number(opt('settle', 2500))

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch()
const problems = []

for (const theme of themes) {
  for (const width of widths) {
    const context = await browser.newContext({
      viewport: { width, height: 900 },
      colorScheme: theme,
      deviceScaleFactor: 1,
    })
    const page = await context.newPage()

    page.on('console', (m) => {
      if (m.type() === 'error') problems.push(`[${theme}/${width}] console: ${m.text().slice(0, 200)}`)
    })
    page.on('requestfailed', (r) => {
      problems.push(`[${theme}/${width}] request failed: ${r.url().slice(0, 120)}`)
    })
    page.on('response', (r) => {
      if (r.status() >= 400) problems.push(`[${theme}/${width}] HTTP ${r.status()} ${r.url().slice(0, 120)}`)
    })

    await page.goto(url, { waitUntil: 'networkidle' }).catch(() => {})
    // The grid and tree both load after first paint; networkidle alone lands mid-skeleton.
    await page.waitForTimeout(settle)

    // The app opens on the credentialed project, which is the sandbox and holds one requirement —
    // fine for the app, useless for a screenshot. Switch by INDEX, never by name: project names
    // belong to other teams and must not appear in this repo.
    const projectIndex = Number(opt('project-index', '-1'))
    if (projectIndex >= 0) {
      const select = page.locator('select').first()
      const value = await select.locator('option').nth(projectIndex).getAttribute('value')
      if (value) {
        await select.selectOption(value)
        // ⚠️ Wait for the switch to actually land. The root row appears almost immediately and the
        // content wait below is satisfied by it, so without this the capture happens while the
        // first level is still in flight — a screenshot of an empty tree that looks exactly like
        // the project-switch bug this harness was used to diagnose. It cost one wrong diagnosis.
        await page.waitForTimeout(4000)
      }
    }

    // Wait for CONTENT, not for a fixed duration. A timeout long enough on a fast day is a
    // timeout too short on a slow one, and the failure mode is a screenshot of a skeleton that
    // looks exactly like a broken render — which is precisely how this script misled once.
    await page
      .waitForSelector('.treegrid tbody tr, .data-grid tbody tr, .grid-status, .treegrid-state', {
        timeout: 30000,
      })
      .catch(() => console.log('  (no rows appeared within 30s)'))

    // Click an explicit theme button. Worth exercising separately from `colorScheme` above:
    // that only drives prefers-color-scheme, while this drives the data-theme override, and the
    // interesting cases are the two where they disagree.
    const setTheme = opt('set-theme')
    if (setTheme) {
      const label = setTheme === 'system' ? 'Follow the system theme' : `Always ${setTheme}`
      await page.getByRole('button', { name: label }).click().catch(() => {})
      await page.waitForTimeout(400)
      const applied = await page.evaluate(() => ({
        attr: document.documentElement.getAttribute('data-theme'),
        bg: getComputedStyle(document.body).backgroundColor,
        surface: getComputedStyle(document.documentElement).getPropertyValue('--surface').trim(),
      }))
      console.log(`  os=${theme} chose=${setTheme} -> data-theme=${applied.attr} --surface=${applied.surface}`)
    }

    // Open a couple of levels so the hierarchy is visible rather than a single root row.
    for (let i = 0; i < Number(opt('expand', '0')); i++) {
      const twisty = page.locator('.treegrid-twisty[aria-expanded="false"]').first()
      if ((await twisty.count()) === 0) break
      await twisty.click()
      await page.waitForTimeout(600)
    }

    // Select a row so the detail pane shows a record instead of its empty state.
    if (args.includes('--select') || opt('select-row')) {
      const index = Number(opt('select-row', '1'))
      const row = page.locator('.treegrid tbody tr, .data-grid tbody tr').nth(index)
      if ((await row.count()) > 0) {
        await row.click()
        await page.waitForTimeout(1400)
      }
    }

    // Switch module via ALM's own left rail, e.g. --module "Test Lab".
    const wantModule = opt('module')
    if (wantModule) {
      await page.getByRole('button', { name: wantModule, exact: true }).click().catch(() => {})
      await page.waitForTimeout(2500)
    }

    // Open a named tab in the detail pane, e.g. --tab Description.
    const wantTab = opt('tab')
    if (wantTab) {
      const tabButton = page.getByRole('tab', { name: new RegExp(wantTab, 'i') }).first()
      await tabButton.click().catch(() => {})
      // Wait for the panel's CONTENT, not a fixed delay. A related-entity tab fetches its rows on
      // open, so 500ms captured the loading skeleton — which looks exactly like a broken render.
      //
      // ⚠️ `.detail-fields` is deliberately NOT in this list, though the Details panel uses it.
      // It is on screen before the click, so including it satisfied the wait instantly and the
      // capture happened while the requested tab was still loading — the same failure this wait
      // exists to prevent, reintroduced by widening the selector.
      await page
        .waitForSelector(
          '.related-table, .related-empty, .detail-memo-rich, .detail-memo-body, ' +
            '.detail-memo-empty, .history-list, .history-empty',
          { timeout: 15000 },
        )
        .catch(() => console.log(`  (tab "${wantTab}" showed no content within 15s)`))
    }

    // Pin the tab rail open so a screenshot shows its labels. Without this the rail is a 40px
    // column of icons in every capture, which is correct behaviour and useless for review.
    if (args.includes('--pin-rail')) {
      await page.locator('.rail-pin').click().catch(() => {})
      await page.waitForTimeout(400)
    }

    // Follow the drill-in: open the related table as the main grid.
    if (args.includes('--drill')) {
      await page.getByRole('button', { name: /open as grid/i }).first().click().catch(() => {})
      await page
        .waitForSelector('.data-grid tbody tr, .grid-status', { timeout: 15000 })
        .catch(() => console.log('  (drill-in showed no rows within 15s)'))
      await page.waitForTimeout(900)
    }

    // Park the pointer away from the rail. Clicking a tab leaves the mouse over it, and the rail
    // expands on hover — so every capture otherwise showed it half-open, which is a real state but
    // never the one being reviewed.
    if (!args.includes('--pin-rail')) {
      await page.mouse.move(10, 500)
      await page.waitForTimeout(400)
    }

    // Blank the project selector before capturing. A screenshot of the running app otherwise
    // carries a borrowed project's real name, which the read-only grant forbids reproducing.
    // Record data in the rows is masked separately with --mask.
    await page
      .evaluate((mask) => {
        const select = document.querySelector('.app-bar-right select')
        if (select) {
          for (const option of select.options) option.textContent = 'PROJECT'
        }
        if (!mask) return
        // Blur the value cells only; headers, chrome and layout stay legible.
        for (const cell of document.querySelectorAll(
          '.treegrid-name, .data-grid tbody td, .treegrid tbody td:not(.treegrid-namecell), .detail-body, .detail-title',
        )) {
          cell.style.filter = 'blur(4px)'
        }
      }, args.includes('--mask'))
      .catch(() => {})

    const name = [tag, theme, `${width}w`].filter(Boolean).join('-')
    await page.screenshot({ path: path.join(outDir, `${name}.png`), fullPage: false })
    console.log(`wrote .shots/${name}.png`)
    await context.close()
  }
}

await browser.close()

if (problems.length > 0) {
  console.log('\nproblems observed:')
  for (const p of [...new Set(problems)]) console.log('  ' + p)
} else {
  console.log('\nno console errors, failed requests or 4xx/5xx responses')
}

console.log('\n.shots now holds:', (await readdir(outDir)).join(', '))
