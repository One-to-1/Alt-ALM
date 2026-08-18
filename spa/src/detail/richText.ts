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
 * **Images.** A memo's `<img>` points at an absolute `/qcbin` REST URL, and the browser cannot
 * fetch it: it is a different origin, it needs an ALM session cookie the browser does not hold, and
 * Alt-ALM has no attachment proxy yet. Leaving the `src` in place would therefore buy a broken-image
 * icon *and* an outbound request to a host named by the memo's author. Dropping the `src` while
 * keeping the element leaves the alt text and a marker we can style — the reader learns an image is
 * there and that we did not show it, which is the true statement.
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

/** Sanitises one memo document. Returns empty html for empty, blank, or text-free input. */
export function sanitizeMemo(html: string): SanitizedMemo {
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

  // An `<img>` with no `src` is not neutral: the browser draws its own broken-image glyph next to
  // the alt text, which reads as a bug in Alt-ALM rather than as a fact about the record. Swapping
  // in a span lets the placeholder be styled and say what it means. `textContent` is an assignment,
  // not a parse, so the alt text cannot reintroduce markup here.
  fragment.querySelectorAll('img[data-blocked]').forEach((img) => {
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
