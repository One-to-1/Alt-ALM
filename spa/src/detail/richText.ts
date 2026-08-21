import DOMPurify from 'dompurify'

/**
 * Renders an ALM memo field's HTML without inheriting its authors' privileges.
 *
 * <h2>Why this file exists at all</h2>
 *
 * A memo field is not a string with some markup in it. It is a full `<html><body>` document that
 * ALM stored verbatim from whoever last edited the record — in the projects Alt-ALM reads, that is
 * other teams, not us. Handing that to `dangerouslySetInnerHTML` is stored XSS by construction: one
 * `<img onerror>` in one requirement's Description and every Alt-ALM user who opens that record is
 * running the author's script with our session.
 *
 * So the choice was never "render it or not", it was "what sanitises it". Three things decided it:
 *
 * 1. **Not hand-rolled.** An allowlist over a regex or over our own parse of the string is the
 *    single most reliably wrong way to do this. The attacks that beat homegrown sanitisers are not
 *    the obvious `<script>`; they are parser differentials, where the sanitiser's idea of the
 *    document and the browser's disagree.
 * 2. **In the browser, not the BFF.** The BFF is our enforcement point for write hazards (ADR 0001)
 *    and sanitising there was the tempting symmetry. But a server-side sanitiser parses with a
 *    *different* HTML parser than the one that will finally render the string, and that gap is
 *    precisely the mXSS class above. Sanitising in the same engine that renders removes the gap
 *    rather than arguing about its width.
 * 3. **DOMPurify.** It is the library that class of bug is reported against and fixed in.
 *
 * The tests in `richText.test.ts` are payloads, not examples — that is the point of them.
 *
 * <h2>ALM strips hostile markup too, and that is not a reason to relax any of this</h2>
 *
 * Probe 27 sent `<script>`, `onerror`, a `javascript:` href and a remote `url()` into a memo and got
 * none of them back. It would be easy to read that as "the server already handles it". It is not:
 * what ALM applies is **output sanitisation**, its own documentation says it *"removes or encodes
 * data returned by requests"*, and the raw value stays in the database. It is configured **per
 * field** in project customization — *Do nothing* / *Text encoding* / *HTML sanitization* — against
 * a `sanitizer-whitelist.xml` owned by whoever deployed the server.
 *
 * So the filtering is a setting, not a property: a project whose Description field is set to *Do
 * nothing* hands us the payload live, and nobody would tell us it had been changed. This sanitiser
 * is the only filter in the chain that does not depend on that setting, which makes it load-bearing
 * rather than belt-and-braces.
 *
 * The one thing ALM does **not** strip in any configuration is a remote `<img src>` — see the image
 * handling below, which is ours to do.
 *
 * <h2>What we allow, and why it is not DOMPurify's default</h2>
 *
 * The default profile is far wider than any memo needs. This list is what ALM's own rich-text
 * editor emits: prose, lists, tables, and the `<font>`/inline-style formatting its toolbar
 * produces. Everything else is dropped even though DOMPurify would consider it safe — a memo has no
 * business containing a form, and narrowing the surface costs us nothing we can see.
 *
 * ⚠️ **This allowlist is deployment-specific.** It is drawn from what our sandbox and PROJECT-5
 * store today. A project whose memos carry markup we have not seen will lose that markup silently.
 * Re-verify against the target instance before assuming a memo renders whole.
 */

/** Prose, lists, tables, and ALM's toolbar formatting. Nothing structural, nothing interactive. */
const ALLOWED_TAGS = [
  'p', 'br', 'div', 'span', 'hr',
  'b', 'strong', 'i', 'em', 'u', 's', 'strike', 'sub', 'sup', 'small', 'big', 'font', 'mark',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'dl', 'dt', 'dd',
  'blockquote', 'pre', 'code',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'caption', 'colgroup', 'col',
  'a', 'img',
]

/**
 * `style` is on this list deliberately and is the one entry worth arguing about. ALM's editor puts
 * nearly all its formatting in inline styles, so dropping it renders most memos as undifferentiated
 * text — the exact failure the plain-text version already had. What makes it defensible is not
 * DOMPurify, which passes CSS through untouched, but {@link BANNED_DECLARATION} below.
 */
const ALLOWED_ATTR = [
  'style', 'class', 'align', 'valign', 'dir', 'lang', 'title',
  'color', 'face', 'size',
  'href', 'target', 'rel',
  'src', 'alt', 'width', 'height',
  'colspan', 'rowspan', 'span', 'border', 'cellpadding', 'cellspacing',
  'start', 'type', 'value',
]

/**
 * CSS declarations we take out ourselves, because DOMPurify does not sanitise CSS.
 *
 * That is a deliberate choice on its part rather than an oversight: `expression()` died with IE and
 * `url(javascript:…)` does not execute in any current browser, so neither is a live script vector.
 * The one that still bites is plain `url(https://…)` — a background image is an outbound request to
 * a host the memo's author chose, which is the same beacon we refuse to let `<img>` send, only
 * quieter. Since we are filtering `url()` anyway, the dead vectors go with it; they cost nothing to
 * remove and their presence in a rendered attribute would fail a security review on sight.
 *
 * Declaration-level, not property-level: an unrecognised declaration is kept. The goal is to render
 * ALM's formatting faithfully, and we do not have an inventory of what its editor emits.
 */
const BANNED_DECLARATION = /url\s*\(|expression\s*\(|behaviou?r\s*:|-moz-binding|@import/i

function filterStyle(style: string): string {
  return style
    .split(';')
    .map((d) => d.trim())
    .filter((d) => d !== '' && !BANNED_DECLARATION.test(d))
    .join('; ')
}

/** Set by the hook during a sanitize() call, read immediately after it returns. */
let blockedImages = 0

/**
 * Two fixes DOMPurify cannot make for us, because both are policy rather than safety.
 *
 * **Images.** A memo's `<img>` points at an absolute `/qcbin` REST URL, which the browser cannot
 * fetch: different origin, and it needs an ALM session cookie the browser does not hold. Leaving the
 * `src` alone would buy a broken-image icon *and* an outbound request to a host named by the memo's
 * author.
 *
 * <p>So the `src` always comes off here, unconditionally, and the original is parked on a
 * `data-alm-src` attribute for the pass below to consider. ⚠️ That ordering is the safety
 * property: no attacker-supplied URL survives sanitisation, and the only thing that can put one back
 * is a URL <em>this app builds</em> from an attachment id it looked up. An `<img src>` restored from
 * the memo's own text would be the beacon this exists to prevent.
 *
 * **Links.** A memo link opening in this tab would navigate away from Alt-ALM and take the SPA's
 * state with it, and `rel` keeps the opened page away from `window.opener`.
 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  const style = node.getAttribute?.('style')
  if (style) {
    const kept = filterStyle(style)
    if (kept) node.setAttribute('style', kept)
    else node.removeAttribute('style')
  }
  if (node.tagName === 'IMG') {
    const src = node.getAttribute('src') ?? ''
    // Inline images survive: they carry their own bytes and reach nothing.
    if (!/^data:image\//i.test(src)) {
      node.removeAttribute('src')
      // Marked, not relabelled: the placeholder built below owns the wording, and an alt forced
      // in here would come back as "image: image".
      node.setAttribute('data-blocked', '')
      // Parked for the resolve pass. Never restored as a src — only used to work out WHICH
      // attachment the memo meant, so a URL of ours can be built for it.
      if (src) node.setAttribute('data-alm-src', src)
      blockedImages += 1
    }
  }
  if (node.tagName === 'A' && node.hasAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

export interface SanitizedMemo {
  /** Safe to hand to `dangerouslySetInnerHTML`, and safe nowhere else. */
  html: string
  /** How many images were left unrendered because Alt-ALM cannot fetch them. */
  blockedImages: number
  /** Whether the source looked like it was carrying something executable. Messaging only. */
  hostile: boolean
}

/**
 * Detects, for the *notice only*, whether the document appeared to carry executable content.
 *
 * This is a heuristic and is not load-bearing: nothing about the sanitiser's behaviour depends on
 * it, and a payload it misses is still removed. It exists because the honest alternative —
 * `DOMPurify.removed.length > 0` — fires on every ALM memo ever written, since they all arrive
 * wrapped in `<html><head><meta>` that is stripped as a matter of course. A banner that is always
 * on says nothing.
 */
function looksHostile(html: string): boolean {
  return /<\s*(script|iframe|object|embed|form)\b/i.test(html)
    || /\son[a-z]+\s*=/i.test(html)
    || /javascript\s*:/i.test(html)
}

/**
 * The attachments Alt-ALM is willing to render for the record a memo belongs to.
 *
 * Keyed by **filename**, because that is what ALM writes into a memo's `src` — the id never appears
 * in the document. Values are URLs this app built (see `attachmentImageUrl`).
 *
 * ⚠️ Absent, or missing an entry, means the image is not shown. That is the safe direction: an
 * unresolved image becomes a labelled placeholder, never a request to whatever host the memo named.
 */
export type MemoImages = Record<string, string>

/**
 * Turns a memo's `<img src>` into the name of the attachment it refers to.
 *
 * ALM writes an absolute REST URL ending in `/attachments/<filename>`, so the filename is the last
 * path segment. Query and fragment are dropped and percent-escapes are decoded, because ALM encodes
 * the name into the URL and the list reports it raw.
 *
 * ⚠️ <strong>The host is deliberately not checked.</strong> That looks like a gap and is not: the
 * name is only ever used as a key into {@link MemoImages}, which holds this record's own
 * attachments and URLs this app built. So a memo pointing at
 * `https://somewhere-else/attachments/spec.png` either matches an attachment of this record — and
 * renders *ours*, from our origin — or matches nothing and stays a placeholder. Neither outcome
 * sends a request to the host the memo named, which is the property worth having. Checking for
 * `/qcbin/` instead would buy nothing and would silently stop working on a deployment that uses a
 * different context path.
 */
function attachmentNameOf(src: string): string {
  const path = src.split('?')[0].split('#')[0]
  const marker = path.lastIndexOf('/attachments/')
  if (marker < 0) return ''
  const name = path.slice(marker + '/attachments/'.length)
  if (!name || name.includes('/')) return ''
  try {
    return decodeURIComponent(name)
  } catch {
    // A malformed escape is not a name. Falling back to the raw text would key the map on
    // something the list can never have reported.
    return ''
  }
}

/**
 * Sanitises one memo document. Returns empty html for empty, blank, or text-free input.
 *
 * @param images this record's attachments by filename, so images stored in ALM can be rendered
 *   through Alt-ALM's own image route. Omit it and every image stays a placeholder — which is what
 *   a caller that has not loaded the list yet should do, rather than showing a broken one.
 */
export function sanitizeMemo(html: string, images?: MemoImages): SanitizedMemo {
  if (!html || !html.trim()) return { html: '', blockedImages: 0, hostile: false }

  blockedImages = 0
  const fragment = DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    // The memo arrives as a whole document; we want its body's contents, not a nested document.
    WHOLE_DOCUMENT: false,
    // ⚠️ No `USE_PROFILES` here, and it is not an omission. Setting it **overrides ALLOWED_TAGS
    // entirely** rather than intersecting with it — with `USE_PROFILES: { html: true }` this
    // sanitiser silently allowed `<form>` and `<input>` through the narrow list above, and the only
    // reason we know is that a test asserted on the output instead of on the configuration.
    // An explicit ALLOWED_TAGS excludes SVG and MathML by construction anyway, which is all the
    // profile was wanted for.
    // `data-*` on a memo element means nothing to us and reaching our own code is not its job.
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    // A fragment rather than a string, so the swap below happens on the sanitised DOM instead of
    // on markup we would have to re-parse.
    RETURN_DOM_FRAGMENT: true,
  })

  // Two outcomes for a blocked image, and the order matters: try to point it at one of THIS
  // record's attachments first, and fall back to the placeholder only when that fails.
  //
  // An `<img>` with no `src` is not neutral: the browser draws its own broken-image glyph next to
  // the alt text, which reads as a bug in Alt-ALM rather than as a fact about the record. Swapping
  // in a span lets the placeholder be styled and say what it means. `textContent` is an assignment,
  // not a parse, so the alt text cannot reintroduce markup here.
  fragment.querySelectorAll('img[data-blocked]').forEach((img) => {
    const resolved = images ? images[attachmentNameOf(img.getAttribute('data-alm-src') ?? '')] : undefined
    img.removeAttribute('data-alm-src')
    if (resolved) {
      // ⚠️ `resolved` is a URL from {@link MemoImages} — built by this app around an attachment
      // id, never taken from the document. The BFF's image route additionally refuses to serve
      // anything whose bytes are not really a raster image, so this cannot become a script tag's
      // slower cousin.
      img.removeAttribute('data-blocked')
      img.setAttribute('src', resolved)
      img.setAttribute('loading', 'lazy')
      blockedImages -= 1
      return
    }
    const chip = document.createElement('span')
    chip.className = 'memo-image-blocked'
    // The whole label is built here rather than half here and half in CSS, so an image with no
    // alt reads "image" instead of "image: image".
    const alt = img.getAttribute('alt')
    chip.textContent = alt ? `image: ${alt}` : 'image'
    img.replaceWith(chip)
  })

  const box = document.createElement('div')
  box.appendChild(fragment)

  return { html: box.innerHTML, blockedImages, hostile: looksHostile(html) }
}

/**
 * Extracts readable plain text from a memo document.
 *
 * Still needed after all of the above: grid cells show a one-line preview where markup would be
 * noise, and the detail pane offers plain text as an escape hatch for the memos whose formatting is
 * worse than none. `DOMParser` builds a detached document — nothing is executed and nothing enters
 * the live DOM — so this path is safe independently of the sanitiser.
 */
export function memoToPlainText(html: string): string {
  try {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    // `textContent` on the body includes the *source* of any script or style element sitting in it,
    // so a preview would read "p{color:red} The requirement shall…" — or, worse, would show an
    // attack's payload as though it were the record's text.
    doc.body.querySelectorAll('script, style, noscript, template').forEach((el) => el.remove())
    return (doc.body.textContent ?? '').trim().replace(/\s+/g, ' ')
  } catch {
    return html.replace(/<[^>]*>/g, ' ').trim().replace(/\s+/g, ' ')
  }
}
