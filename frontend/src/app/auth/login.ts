import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth } from '../core/auth';
import { errorMessage, Notice } from '../core/notice';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Login {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly notice = inject(Notice);
  readonly registration = signal(this.route.snapshot.data['register'] === true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly returnUrl = this.destination();
  readonly form = inject(FormBuilder).nonNullable.group({
    fullName: [
      '',
      this.registration()
        ? [Validators.required, Validators.pattern(/\S/), Validators.maxLength(150)]
        : [],
    ],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: [
      '',
      this.registration()
        ? [Validators.required, Validators.pattern(/^(?=.*[A-Za-z])(?=.*[0-9]).{8,72}$/)]
        : [Validators.required, Validators.maxLength(72)],
    ],
  });

  async submit() {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    const { email, password, fullName } = this.form.getRawValue();
    try {
      if (this.registration()) {
        await firstValueFrom(this.auth.register(email.trim(), password, fullName.trim()));
        this.registration.set(false);
        this.form.controls.fullName.clearValidators();
        this.form.controls.fullName.updateValueAndValidity();
      }
      await firstValueFrom(this.auth.login(email.trim(), password));
      this.form.reset();
      this.notice.show('Signed in. You can now reserve tickets and view your orders.');
      await this.router.navigateByUrl(this.returnUrl);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  private destination() {
    const value = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/events';
    return /^\/(events(?:\/[a-f0-9-]+)?|orders|tickets)(?:\?|$)/.test(value) ? value : '/events';
  }
}
