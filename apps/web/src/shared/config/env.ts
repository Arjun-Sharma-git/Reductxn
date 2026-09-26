import { z } from 'zod';

/**
 * The client's environment, parsed rather than read.
 *
 * `import.meta.env` is `any`-shaped and permissive: a missing variable is `undefined`, which becomes a request
 * to the string "undefined" somewhere far from here. Parsing it at startup with Zod means a deployment's
 * mistake is a sentence in the console before the first render, rather than a broken screen that looks like a
 * backend fault.
 *
 * There is deliberately **no** default API base URL pointing at a host: in development Vite proxies `/v1` to the
 * API (see `vite.config.ts`) and in a deployment the client is served from the same origin behind a reverse
 * proxy. An empty base means "this origin", which is true in both.
 */
const schema = z.object({
  VITE_API_BASE_URL: z
    .string()
    .regex(/^$|^https?:\/\/[^/]+$/, 'VITE_API_BASE_URL must be empty or an origin, with no trailing slash')
    .default(''),
});

const parsed = schema.safeParse(import.meta.env);

if (!parsed.success) {
  throw new Error(`the client's environment is invalid: ${parsed.error.message}`);
}

export const env = {
  /** Empty means this origin: the dev proxy locally, the reverse proxy in a deployment. */
  apiBaseUrl: parsed.data.VITE_API_BASE_URL,
  /**
   * The product's own name, and the one string on screen that is not in a catalog.
   *
   * RULE F10 allows a literal with an `// i18n-ignore: <reason>` marker, and configuration is a stronger form of
   * the same statement: a brand name is not copy, so it must not be rewordable by a translator and must not
   * appear in a catalog where somebody would feel obliged to translate it. Keeping it here rather than in the
   * markup is also what lets the lint rule stay absolute about JSX text.
   */
  appName: 'reductxn',
} as const;
