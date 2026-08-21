import { describe, expect, it } from 'vitest'
import { memoToPlainText, sanitizeMemo } from './richText.ts'

/**
 * The sanitiser's payload suite.
 *
 * Every case in the first block is an attack, and the assertion is always the same shape: the
 * rendered string must not contain the executable part. They are written against the *output*
 * rather than against DOMPurify's configuration, because the configuration is not what protects
 * anyone — a passing test here would still pass if we swapped the library, which is the property we
 * want from a test guarding a security boundary.
 *
 * The mutation-XSS cases are the reason this file is worth its length. Each is a string that a
 * naive sanitiser accepts because its parse of the document differs from the browser's; they are
 * the specific class of bug that decided against writing our own (see richText.ts).
 */

/** What must never survive, in any form, from any input. */
function assertInert(html: string) {
  expect(html.toLowerCase()).not.toContain('<script')
  expect(html.toLowerCase()).not.toContain('javascript:')
  expect(html).not.toMatch(/\son[a-z]+\s*=/i)
  expect(html.toLowerCase()).not.toContain('<iframe')
  expect(html.toLowerCase()).not.toContain('<object')
  expect(html.toLowerCase()).not.toContain('<embed')
}

describe('sanitizeMemo — what it refuses', () => {
  it('drops a script element and its contents', () => {
    const { html } = sanitizeMemo('<p>before</p><script>alert(1)</script><p>after</p>')
    assertInert(html)
    // The prose either side survives: removing the record's text along with the payload would be
    // its own kind of wrong answer.
    expect(html).toContain('before')
    expect(html).toContain('after')
    expect(html).not.toContain('alert(1)')
  })

  it('drops event-handler attributes while keeping the element', () => {
    const { html } = sanitizeMemo('<p onclick="steal()">click me</p>')
    assertInert(html)
    expect(html).toContain('click me')
  })

  it('drops the img onerror payload — the one that actually reaches people', () => {
    // This is the realistic attack on this app: it needs no interaction, only that someone opens
    // the record.
    const { html } = sanitizeMemo('<img src=x onerror=alert(document.cookie)>')
    assertInert(html)
    expect(html).not.toContain('document.cookie')
  })

  it('drops a javascript: href but keeps the link text', () => {
    const { html } = sanitizeMemo('<a href="javascript:alert(1)">read the spec</a>')
    assertInert(html)
    expect(html).toContain('read the spec')
  })

  it('drops javascript: hidden behind entity encoding and whitespace', () => {
    const { html } = sanitizeMemo('<a href="ja&#118;ascr&#105;pt&colon;alert(1)">x</a>')
    assertInert(html)
    const { html: spaced } = sanitizeMemo('<a href="java\tscript:alert(1)">x</a>')
    assertInert(spaced)
  })

  it('drops iframe, object, embed and form', () => {
    const { html } = sanitizeMemo(
      '<iframe src="//evil"></iframe><object data="x"></object>'
        + '<embed src="x"><form action="//evil"><input name="p"></form>',
    )
    assertInert(html)
    expect(html.toLowerCase()).not.toContain('<form')
    expect(html.toLowerCase()).not.toContain('<input')
  })

  it('drops svg and mathml entirely — no memo needs either, and both are where mXSS lives', () => {
    const { html } = sanitizeMemo('<svg><desc><![CDATA[</desc><script>alert(1)</script>]]></svg>')
    assertInert(html)
    expect(html.toLowerCase()).not.toContain('<svg')
  })

  it('survives the noscript parser differential', () => {
    // Parsed one way this is an attribute; parsed the other it closes the element and opens an img.
    // A sanitiser that disagrees with the browser about which lets the payload through.
    const { html } = sanitizeMemo(
      '<noscript><p title="</noscript><img src=x onerror=alert(1)>"></p></noscript>',
    )
    assertInert(html)
  })

  it('survives the style/comment differential', () => {
    const { html } = sanitizeMemo('<style><!--</style><img src=x onerror=alert(1)>--></style>')
    assertInert(html)
  })

  it('does not leave a style rule that can load or execute anything', () => {
    const { html } = sanitizeMemo(
      '<p style="background:url(javascript:alert(1));width:expression(alert(1))">x</p>',
    )
    assertInert(html)
    expect(html.toLowerCase()).not.toContain('expression(')
  })

  it('drops a data: URI that is a document rather than an image', () => {
    const { html } = sanitizeMemo('<a href="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">x</a>')
    expect(html.toLowerCase()).not.toContain('data:text/html')
  })
})

describe('sanitizeMemo — what it keeps', () => {
  it('keeps the formatting ALM’s own editor emits', () => {
    const { html } = sanitizeMemo(
      '<html><body><p><b>Bold</b> and <i>italic</i> and <u>underlined</u>.</p>'
        + '<ul><li>first</li><li>second</li></ul>'
        + '<table border="1"><tr><td colspan="2">cell</td></tr></table>'
        + '<font color="#ff0000" size="3">red</font></body></html>',
    )
    expect(html).toContain('<b>Bold</b>')
    expect(html).toContain('<li>first</li>')
    expect(html).toContain('colspan="2"')
    expect(html).toContain('<font')
    expect(html).toContain('color="#ff0000"')
  })

  it('keeps inline styles, which is where ALM puts nearly all of its formatting', () => {
    const { html } = sanitizeMemo('<p style="color: rgb(255, 0, 0); font-weight: bold">x</p>')
    expect(html).toContain('color: rgb(255, 0, 0)')
    expect(html).toContain('font-weight: bold')
  })

  it('unwraps the document wrapper rather than nesting a second document', () => {
    const { html } = sanitizeMemo(
      '<html><head><meta charset="utf-8"><title>t</title></head><body><p>body text</p></body></html>',
    )
    expect(html).toContain('<p>body text</p>')
    expect(html.toLowerCase()).not.toContain('<html')
    expect(html.toLowerCase()).not.toContain('<body')
    // The head's contents must not leak into the rendered text as stray words.
    expect(html).not.toContain('utf-8')
  })

  it('forces links to open away from the SPA and without window.opener', () => {
    const { html } = sanitizeMemo('<a href="https://example.invalid/spec">spec</a>')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer nofollow"')
  })
})

describe('sanitizeMemo — images', () => {
  it('keeps an inline data: image, which carries its own bytes and reaches nothing', () => {
    const gif = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=='
    const { html, blockedImages } = sanitizeMemo(`<img src="${gif}" alt="dot">`)
    expect(html).toContain(gif)
    expect(blockedImages).toBe(0)
  })

  it('strips the src of an image we cannot fetch, and says so rather than beaconing', () => {
    const { html, blockedImages } = sanitizeMemo(
      '<img src="https://alm.example.invalid/qcbin/rest/../attachment.png" alt="diagram">',
    )
    // No src at all: an outbound request to a host the memo’s author chose is the thing being
    // refused, and the element goes with it so no broken-image glyph is drawn either.
    expect(html).not.toContain('src=')
    expect(html).not.toContain('<img')
    expect(html).toContain('memo-image-blocked')
    // The alt text survives as the placeholder's label — it is what tells the reader what is
    // missing.
    expect(html).toContain('>image: diagram<')
    expect(blockedImages).toBe(1)
  })

  it('counts every blocked image, so the notice can be specific', () => {
    const { blockedImages } = sanitizeMemo('<img src="/a.png"><img src="/b.png"><img src="/c.png">')
    expect(blockedImages).toBe(3)
  })

  it('labels a blocked image with no alt so it is not an invisible hole', () => {
    const { html } = sanitizeMemo('<img src="/a.png">')
    expect(html).toContain('>image<')
  })

  it('never lets the alt text become markup again on its way into the placeholder', () => {
    // The placeholder is built from an attribute of the source document, so the round trip
    // attribute → element text is a place markup could be reintroduced. DOMPurify drops this alt
    // outright and the fallback label is used, which is why the assertion is about the payload
    // being gone rather than about it being escaped: either outcome is safe, only one is true.
    const { html } = sanitizeMemo('<img src="/a.png" alt="<script>alert(1)</script>">')
    assertInert(html)
    expect(html).not.toContain('alert(1)')
    expect(html).toContain('>image<')
  })

  it('resets the count between calls', () => {
    sanitizeMemo('<img src="/a.png">')
    expect(sanitizeMemo('<p>no images here</p>').blockedImages).toBe(0)
  })
})

describe('sanitizeMemo — images stored in ALM', () => {
  /**
   * The name a memo's <img src> ends in, mapped to the URL Alt-ALM would serve it from.
   *
   * Keyed by FILENAME because that is what ALM writes into the document — an attachment's id never
   * appears in a memo. The value is always a URL this app built; nothing from the memo becomes a
   * src.
   */
  const IMAGES = {
    'diagram.png': '/api/attachments/requirements/7001/8/image?project=DOM%2FPROJ',
  }

  const ALM_SRC =
    'https://alm.example.invalid/qcbin/rest/domains/DOM/projects/PROJ' +
    '/requirements/7001/attachments/diagram.png'

  it('renders an image that IS filed against this record', () => {
    const { html, blockedImages } = sanitizeMemo(`<img src="${ALM_SRC}" alt="diagram">`, IMAGES)

    expect(html).toContain(IMAGES['diagram.png'])
    expect(html).toContain('<img')
    // Not a placeholder any more, and not counted as one — the notice would otherwise claim an
    // image is missing while the reader is looking at it.
    expect(html).not.toContain('memo-image-blocked')
    expect(blockedImages).toBe(0)
  })

  it('leaves an image that is NOT filed against this record as a placeholder', () => {
    const other = ALM_SRC.replace('diagram.png', 'somebody-elses.png')
    const { html, blockedImages } = sanitizeMemo(`<img src="${other}" alt="chart">`, IMAGES)

    expect(html).not.toContain('<img')
    expect(html).toContain('memo-image-blocked')
    expect(blockedImages).toBe(1)
  })

  it('shows placeholders while the attachment list is still loading', () => {
    // ⚠️ Undefined is "not known yet", and it must render the same as unresolvable rather than
    // guessing a URL. A src invented before the list arrives is a request to a host we have not
    // checked.
    const { html, blockedImages } = sanitizeMemo(`<img src="${ALM_SRC}" alt="diagram">`)

    expect(html).not.toContain('<img')
    expect(blockedImages).toBe(1)
  })

  it("⚠️ never emits the memo's own URL, even when the filename matches", () => {
    // The deliberate design point. A memo pointing at somebody else's host, with a filename that
    // happens to match one of this record's attachments, renders OUR copy from OUR origin. The
    // host is not consulted, so no request is made to it either way.
    const hostile = 'https://evil.example.invalid/attachments/diagram.png'
    const { html } = sanitizeMemo(`<img src="${hostile}" alt="diagram">`, IMAGES)

    expect(html).not.toContain('evil.example.invalid')
    expect(html).toContain(IMAGES['diagram.png'])
  })

  it('never leaks the parked src into the output', () => {
    // `data-alm-src` is scaffolding between the sanitise hook and the resolve pass. Leaving it in
    // would put an attacker-chosen URL back into the DOM, which is exactly what stripping the src
    // was for — inert as an attribute today, one careless CSS or script change from not being.
    const resolved = sanitizeMemo(`<img src="${ALM_SRC}">`, IMAGES).html
    const unresolved = sanitizeMemo('<img src="https://elsewhere.invalid/x.png">', IMAGES).html

    expect(resolved).not.toContain('data-alm-src')
    expect(resolved).not.toContain('alm.example.invalid')
    expect(unresolved).not.toContain('data-alm-src')
    expect(unresolved).not.toContain('elsewhere.invalid')
  })

  it('resolves a percent-encoded filename against the name ALM reports raw', () => {
    // ALM encodes the name into the URL; the list reports it decoded. Comparing the two without
    // decoding would leave every image with a space or an accent in its name unshowable.
    const images = { 'my diagram.png': '/api/attachments/requirements/7001/9/image' }
    const src = ALM_SRC.replace('diagram.png', 'my%20diagram.png')
    const { html } = sanitizeMemo(`<img src="${src}">`, images)

    expect(html).toContain('/api/attachments/requirements/7001/9/image')
  })

  it('does not resolve a src that names no attachment at all', () => {
    const { html, blockedImages } = sanitizeMemo('<img src="/not-an-attachment-url.png">', IMAGES)

    expect(html).toContain('memo-image-blocked')
    expect(blockedImages).toBe(1)
  })

  it('⚠️ a javascript: src is refused before resolution is even considered', () => {
    const { html } = sanitizeMemo('<img src="javascript:alert(1)">', IMAGES)

    assertInert(html)
    expect(html).not.toContain('alert(1)')
  })

  it('counts a mix correctly, so the notice states a true number', () => {
    const other = ALM_SRC.replace('diagram.png', 'missing.png')
    const { blockedImages } = sanitizeMemo(
      `<img src="${ALM_SRC}"><img src="${other}"><img src="/x.png">`,
      IMAGES,
    )

    expect(blockedImages).toBe(2)
  })
})

describe('sanitizeMemo — degenerate input', () => {
  it('treats empty, blank and undefined-ish input as empty', () => {
    expect(sanitizeMemo('').html).toBe('')
    expect(sanitizeMemo('   \n  ').html).toBe('')
  })

  it('does not throw on unbalanced or truncated markup', () => {
    expect(() => sanitizeMemo('<p><b>unclosed <table><tr><td>')).not.toThrow()
    expect(() => sanitizeMemo('<<<>>>&&&')).not.toThrow()
  })

  it('flags apparently-executable input for the notice, and only for the notice', () => {
    expect(sanitizeMemo('<p>ordinary text</p>').hostile).toBe(false)
    // An ALM memo always arrives wrapped in a head the sanitiser strips; that alone must not read
    // as hostile, or the notice is on permanently and means nothing.
    expect(sanitizeMemo('<html><head><meta charset="utf-8"></head><body><p>x</p></body></html>').hostile)
      .toBe(false)
    expect(sanitizeMemo('<img src=x onerror=alert(1)>').hostile).toBe(true)
  })
})

describe('memoToPlainText', () => {
  it('returns the text of a memo document with its whitespace collapsed', () => {
    expect(memoToPlainText('<html><body><p>one</p>\n\n  <p>two</p></body></html>'))
      .toBe('one two')
  })

  it('does not carry markup, script text or head content into the preview', () => {
    const text = memoToPlainText(
      '<html><head><title>t</title><style>p{color:red}</style></head>'
        + '<body><script>alert(1)</script><p>visible</p></body></html>',
    )
    expect(text).toBe('visible')
  })

  it('is empty for an empty document', () => {
    expect(memoToPlainText('<html><body></body></html>')).toBe('')
  })
})
