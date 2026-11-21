import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth } from '../core/auth';
import { Ticket } from '../core/models';
import { errorMessage, Notice } from '../core/notice';
import { TicketingApi } from '../core/ticketing-api';

@Component({
  selector: 'app-tickets',
  imports: [RouterLink],
  templateUrl: './tickets.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Tickets {
  readonly auth = inject(Auth);
  private readonly api = inject(TicketingApi);
  private readonly notice = inject(Notice);
  readonly items = signal<Ticket[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  private generation = 0;

  constructor() {
    effect(() => {
      if (!this.auth.signedIn()) {
        this.generation++;
        this.items.set([]);
      }
    });
    void this.load();
  }
  async load() {
    const generation = ++this.generation;
    this.busy.set(true);
    this.error.set('');
    try {
      const items = await firstValueFrom(this.api.tickets());
      if (generation === this.generation && this.auth.signedIn()) this.items.set(items);
    } catch (error) {
      if (generation === this.generation) this.error.set(errorMessage(error));
    } finally {
      if (generation === this.generation) this.busy.set(false);
    }
  }
  async copy(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      this.notice.show('Código de entrada copiado.');
    } catch {
      this.notice.show(
        'No se pudo copiar automáticamente. Puedes seleccionar el código y copiarlo.',
        true,
      );
    }
  }
  print() {
    window.print();
  }
}
