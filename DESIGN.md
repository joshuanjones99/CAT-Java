# CAT-Java web design

CAT-Java's pages follow the Python CAT's approved browser workspace: electric blue and mint
on a cool light page, with near-black side navigation. The source of truth for that look is the
Python's `src/cat/web/DESIGN.md` and `static/workspace.css`. This file records how the look is
implemented here and the rules that keep it consistent. When the two ports disagree on a visual
rule, the Python's wins. Behaviour (what a page does) is still specified by the Python pages
themselves (HANDOFF §5).

## How the UI is built

- **Server-rendered HTML + htmx** (Javalin 6, htmx 2). Pages build HTML in Java (`web/*Page.java`,
  `*Html.java`) and htmx swaps fragments. Keep it: it is a good fit for a localhost operator console
  whose state lives in the server. It needs no Node toolchain, the HTML is testable as strings, and
  there is no client state to keep in step. The look comes from **one stylesheet**, not from the
  framework.
- **`src/main/resources/public/cat.css`** owns every colour, font, radius and spacing value as a
  `--cat-*` token, light and dark. `web/RunStyle.java` holds the run pages' shared rules and uses the
  same tokens.
- Element defaults (`button`, `input`, `select`, `textarea`) live inside `:where()`, so they have no
  specificity: any page class overrides them without `!important`.
- Fonts are self-hosted in `public/fonts/` (Barlow Semi Condensed 600, Source Sans 3 variable; SIL
  OFL, licence files alongside). CAT never contacts a font CDN.

## Tokens

| Token | Light | Use |
|---|---|---|
| `--cat-blue` | `#1234ff` | primary actions, selection, progress, focus |
| `--cat-mint` | `#6effd2` | healthy results (always dark text `--cat-on-mint`) |
| `--cat-bad` | `#c6263e` | failures, Stop |
| `--cat-amber` | `#ffc857` | warnings (dark text `--cat-on-amber`) |
| `--cat-page` / `--cat-surface` | `#f1f4f5` / `#fff` | page / cards |
| `--cat-ink` / `--cat-muted` | `#172c35` / `#596d76` | text / secondary text |
| `--cat-line` / `--cat-line-2` | `#d6dfe2` / `#eaf0f2` | borders / table heads, tracks |
| `--cat-side` | `#101e26` | sidebar (dark in both appearances) |

Sidebar: 250px wide, 70px as the icon rail (the Python drawer's `width=250 mini-width=70` in
`layout.py`; its DESIGN.md's 224/72 is out of date). The expanded sidebar shows the wide Commander-dots CAT
logo (`icons/cat-commander-logo.png`) centred at the top; the rail shows the monogram. The collapse button is a
28px square straddling the sidebar's edge.

In CAT's installed window (no title bar, HANDOFF §2) Edge's window buttons sit over the page's top right and the
top 33px is the window's drag strip (`.window-drag`): nothing clickable goes there unless it says
`app-region: no-drag`, and the page top is at least the strip's height. The `theme-color` behind Edge's buttons
follows `--cat-page` (`cat.js`); `manifest.json`'s colours are the light `--cat-page`, kept equal by a test.

Radii: tags 5px, controls 7px, cards 12px, dialogs 16px. Spacing: 8 / 16 / 20 / 24 / 32px;
page gutter 32px (24px under 1100px, 16px on phones), page top 28px.

## Type

- Headings: Barlow Semi Condensed 600. Page title 36px (`h1`), dialog title 28px, metric values
  28px, current step 23px.
- Everything else: Source Sans 3. Body 16px/1.5, controls and tables 15px, card headings (`h2`)
  18px/600, labels and metadata 12–14px.
- Monospace (Consolas, Cascadia Mono) only for code, logs, configuration, paths and read-backs.
- Tabular numerals everywhere (`font-variant-numeric: tabular-nums` on `body`).

## Components (the classes to reach for)

| Need | Markup |
|---|---|
| A group of content | `<div class="card">` with an `<h2>` first |
| The one main action in a card or form | `<button class="run-primary">` (or `class="primary"`; a form's `type="submit"` is primary automatically) |
| A secondary action | plain `<button>` (outlined blue) |
| A quiet action beside others, or a link-like action in a row or sentence | `<button class="ghost">` |
| Stop | `<button class="run-danger">` (filled red) |
| A destructive choice that confirms first | `<button class="danger">` (outlined red; filled inside `.cat-dialog`) |
| A status | `<span class="badge ok|warn|error|run|muted">Word</span>`: always a word, never colour alone |
| A one-of-few choice | `<div class="seg">` of `<label><input type="radio">…</label>` or `.seg-btn` buttons |
| Sub-pages | `<nav class="tabs">` of links with `aria-current="page"` |
| A table | `results-table` or `sim-table` (head row on `--cat-line-2`, row hover) |
| A notice | `<p class="run-notice">` (amber edge) |
| A confirmation | `<dialog class="cat-dialog">` with an `<h2>` and a `.sim-actions` footer |

## Rules

1. **Tokens only.** No colour literal (`#hex`, `rgb()`, `hsl()`) and no `style="..."` in
   `com.verifone.cat.web`; add a token or a class to `cat.css`. Enforced by
   `lint/NoInlineStyleTest`.
2. **One primary action per card.** If everything is blue, nothing is. Secondary actions are
   outlined, tertiary ones `ghost`.
3. **Colour is never the only signal.** Status tags carry a word; failures and warnings say what
   happened in text.
4. **Both appearances, every time.** Check a new page in light and dark. A new token gets a dark
   value in both dark blocks of `cat.css` (the `prefers-color-scheme` block and `[data-theme="dark"]`).
5. **The shell owns the frame.** Pages never add outer padding, max-widths or title offsets: `main`
   provides the 32px gutters and the 1760px limit. Content is left-aligned and fluid; only reading
   text is limited to a measure.
6. **Controls are 40px tall** (34px inside a segmented track), with 7px corners, a visible focus ring
   and a hover state.
7. **Monospace is for machine text only.** Prose, labels and choices are never monospace.
8. **No new visual dependency** (component kit, icon font, CSS framework) without a HANDOFF §2 decision.
   Navigation glyphs are the Python's white WebP images, or small inline SVGs (`Layout.NAV_SVGS`) drawn
   to the same 21px box.
9. **Respect reduced motion.** Animations and transitions collapse under `prefers-reduced-motion`.
10. **Screens to check:** 1440px, 768px (drawer navigation) and 390px.
