import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, combineLatest, map, of, startWith, Subject, switchMap, tap } from 'rxjs';
import { EventSummary, Page } from '../core/models';
import { errorMessage } from '../core/notice';
import { TicketingApi } from '../core/ticketing-api';

@Component({
  selector: 'app-catalog',
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  templateUrl: './catalog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Catalog {
  private readonly api = inject(TicketingApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly retry = new Subject<void>();
  readonly query = new FormControl('', { nonNullable: true });
  readonly result = signal<Page<EventSummary> | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor() {
    combineLatest([this.route.queryParamMap, this.retry.pipe(startWith(undefined))])
      .pipe(
        map(([params]) => params),
        tap((params) => {
          this.query.setValue(params.get('q') ?? '');
          this.loading.set(true);
          this.error.set('');
        }),
        switchMap((params) =>
          this.api.events(params.get('q') ?? '', this.pageNumber(params.get('page'))).pipe(
            catchError((error) => {
              this.error.set(errorMessage(error));
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.result.set(result);
        this.loading.set(false);
      });
  }

  search(page = 0) {
    void this.router.navigate(['/eventos'], {
      queryParams: { q: this.query.value.trim() || null, page: page || null },
    });
  }

  reload() {
    this.retry.next();
  }

  private pageNumber(value: string | null) {
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= 0 ? number : 0;
  }
}
