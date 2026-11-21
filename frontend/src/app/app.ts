import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth } from './core/auth';
import { Notice } from './core/notice';
import { Purchase } from './purchase/purchase';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  readonly auth = inject(Auth);
  readonly notice = inject(Notice);
  readonly purchase = inject(Purchase);
  readonly router = inject(Router);
  readonly loggingOut = signal(false);
  private readonly main = viewChild<ElementRef<HTMLElement>>('main');

  focusContent() {
    this.main()?.nativeElement.focus();
  }
  async logout() {
    if (this.loggingOut() || this.purchase.busy()) return;
    this.loggingOut.set(true);
    try {
      await firstValueFrom(this.auth.logout());
      this.notice.show('Sesión cerrada. Tus compras se conservan en tu cuenta.');
    } catch (error) {
      this.notice.error(error);
    } finally {
      this.loggingOut.set(false);
      await this.router.navigate(['/eventos']);
    }
  }
}
