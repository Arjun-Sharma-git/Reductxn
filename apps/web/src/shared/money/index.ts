/**
 * `shared/money`: integer minor units, formatted and parsed for a locale.
 *
 * Everything that touches an amount goes through this barrel, and the ESLint rules that enforce RULE F9, F11 and
 * F12 are what stop the other routes in: no `Intl.NumberFormat` outside this folder (money), no `parseFloat`
 * anywhere, no division by a hundred anywhere. What is exported here is deliberately the whole surface - four
 * functions, a type and a fault - because a wider surface is more places the rules can be worked around.
 */
export {
  exponentOf,
  MAX_AMOUNT_MINOR,
  money,
  MoneyFault,
  type CurrencyInfo,
  type Money,
} from './money';
export { formatMoney, formatMoneyOrRefusal, formatRate } from './format';
export { parseAmount } from './parse';
export { digitsOf, group, type Digits } from './digits';
