import { InjectionToken } from '@angular/core';

/** Prefix for API calls. Empty in the app (same origin, dev proxy); the contract tests point it at Pact's mock server. */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', { factory: () => '' });

/**
 * Offline demo mode: open the app with `?demo=1` to run on built-in seed data
 * with no backend and no sign-in. Read once at startup.
 */
export const DEMO_MODE = new InjectionToken<boolean>('DEMO_MODE', {
  factory: () =>
    typeof location !== 'undefined' && new URLSearchParams(location.search).get('demo') === '1',
});
