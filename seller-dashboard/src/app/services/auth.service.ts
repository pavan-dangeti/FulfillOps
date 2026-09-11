import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly signedIn = signal(false);

  readonly isSignedIn = this.signedIn.asReadonly();

  signIn(): void {
    this.signedIn.set(true);
  }

  signOut(): void {
    this.signedIn.set(false);
  }
}