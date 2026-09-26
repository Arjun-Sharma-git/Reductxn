/**
 * `shared/ui`: the design system's primitives, and the only components that know about money or locale.
 *
 * Deliberately small, and deliberately not a component library. What lives here is what more than one screen
 * needs *and* what encodes a rule: {@link Amount} is the only renderer of money (F9, F11), {@link Notice} and
 * {@link ProblemNotice} are the only renderers of a refusal (B20), the header controls are the only controls
 * with an accessible name by construction rather than by `aria-label`, and {@link Field} is the only place that
 * puts a label, a hint and an error in the order a screen reader reads them. Anything that is simply "a nice
 * button" waits until a second screen wants it - which is exactly how `Field` arrived here from
 * `features/obligations` when the members form needed it (ADR 0035).
 */
export { Amount } from './Amount';
export { AppHeaderControls } from './AppHeaderControls';
export { Card } from './Card';
export { Field } from './Field';
export { Notice, ProblemNotice } from './Notice';
export { applyTheme, resolveTheme, storedPreference, useTheme, type ThemePreference } from './theme';
