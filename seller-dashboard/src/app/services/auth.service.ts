import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API_BASE_URL, DEMO_MODE } from '../api';

interface TokenResponse {
  accessToken: string;
  roles: string[];
}

/**
 * Sellers sign in against order-service and get a one-hour access token.
 * The token lives in memory only (never localStorage), so a reload signs the
 * seller out: less convenient, but nothing for an injected script to lift.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  readonly demo = inject(DEMO_MODE);

  private readonly accessToken = signal<string | null>(null);

  readonly token = this.accessToken.asReadonly();
  readonly isSignedIn = computed(() => this.demo || this.accessToken() !== null);

  /** Rejects if the credentials are wrong or the account is not a seller. */
  async signIn(username: string, password: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.base}/api/auth/token`, { username, password })
    );
    if (!res.roles.includes('SELLER')) {
      throw new Error('This account is not a seller account');
    }
    this.accessToken.set(res.accessToken);
  }

  signOut(): void {
    this.accessToken.set(null);
  }
}
