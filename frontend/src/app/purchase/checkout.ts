import { CurrencyPipe, DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, interval, of, startWith, Subject, switchMap, tap, combineLatest } from 'rxjs';
import { Auth } from '../core/auth';
import { errorMessage } from '../core/notice';
import { TicketingApi } from '../core/ticketing-api';
import { OrderActions } from './order-actions';
import { deliveryLabel, OrderStatus } from './order-status';
import { Purchase } from './purchase';

@Component({
  selector: 'app-checkout',
  imports: [CurrencyPipe, DatePipe, ReactiveFormsModule, RouterLink, OrderActions, OrderStatus],
  templateUrl: './checkout.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Checkout {
  readonly purchase = inject(Purchase);
  readonly auth = inject(Auth);
  private readonly api = inject(TicketingApi);
  private readonly route = inject(ActivatedRoute);
  private readonly retry = new Subject<void>();
  private readonly destroyRef = inject(DestroyRef);
  readonly error = signal('');
  readonly loading = signal(true);
  readonly blocked = signal(false);
  readonly now = signal(Date.now());
  readonly delivery = deliveryLabel;
  readonly form = inject(FormBuilder).nonNullable.group({
    ticketTypeId: ['', Validators.required],
    quantity: [1, [Validators.required, Validators.min(1), Validators.pattern(/^\d+$/)]],
  });
  private readonly selectedId = toSignal(this.form.controls.ticketTypeId.valueChanges, {
    initialValue: '',
  });
  private readonly quantity = toSignal(this.form.controls.quantity.valueChanges, {
    initialValue: 1,
  });
  readonly selected = computed(() =>
    this.purchase.event()?.ticketTypes.find((type) => type.id === this.selectedId()),
  );
  readonly maximum = computed(() =>
    Math.min(this.selected()?.availableQuantity ?? 0, this.selected()?.maxPerOrder ?? 0),
  );
  readonly total = computed(() => (this.selected()?.price ?? 0) * (this.quantity() || 0));
  readonly available = computed(() => {
    const type = this.selected();
    return (
      !!type &&
      type.availableQuantity > 0 &&
      Date.parse(type.saleStartsAt) <= this.now() &&
      Date.parse(type.saleEndsAt) > this.now()
    );
  });
  readonly remaining = computed(
    () =>
      Math.max(
        0,
        Math.ceil((Date.parse(this.purchase.reservation()?.expiresAt ?? '') - this.now()) / 1000),
      ) || 0,
  );
  readonly countdown = computed(
    () => `${Math.floor(this.remaining() / 60)}:${String(this.remaining() % 60).padStart(2, '0')}`,
  );

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now()));
    combineLatest([this.route.paramMap, this.retry.pipe(startWith(undefined))])
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set('');
          this.blocked.set(false);
        }),
        switchMap(([params]) =>
          this.api.event(params.get('id') ?? '').pipe(
            catchError((error) => {
              this.error.set(errorMessage(error));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((event) => {
        this.loading.set(false);
        if (!event) return;
        if (!this.purchase.select(event)) {
          this.blocked.set(true);
          return;
        }
        const first = event.ticketTypes.find(
          (type) =>
            type.availableQuantity > 0 &&
            Date.parse(type.saleStartsAt) <= Date.now() &&
            Date.parse(type.saleEndsAt) > Date.now(),
        );
        this.form.controls.ticketTypeId.setValue(first?.id ?? event.ticketTypes[0]?.id ?? '');
      });
    this.form.controls.ticketTypeId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.form.controls.quantity.setValue(1));
  }

  reserve() {
    this.form.markAllAsTouched();
    if (this.form.invalid || !this.available() || this.quantity() > this.maximum()) return;
    void this.purchase.reserve(
      this.form.controls.ticketTypeId.value,
      this.form.controls.quantity.value,
    );
  }
  reload() {
    this.retry.next();
  }
}
