/**
 * The test environment's own setup, and the reason it exists at all is jsdom.
 *
 * jsdom implements the DOM and not the CSS or media side of a browser, so `matchMedia` is absent - and this app
 * asks it two questions: which colour scheme the user prefers (`shared/ui/theme`) and whether to animate
 * (`shared/gsap/presets`). A default has to be chosen for the tests, and the choice is the *conservative* one:
 * `prefers-reduced-motion: reduce`. Every unit test therefore runs with motion off, so a test that passes cannot
 * be passing because an animation had not finished yet.
 */
if (globalThis.matchMedia === undefined) {
  globalThis.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList;
}
