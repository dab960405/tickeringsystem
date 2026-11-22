import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Login } from './login';

describe('Registration form', () => {
  let backend: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              data: { register: true },
              queryParamMap: convertToParamMap({ returnUrl: 'https://untrusted.example' }),
            },
          },
        },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
  });
  afterEach(() => backend.verify());

  it('does not submit invalid fields and renders feedback', async () => {
    const fixture = TestBed.createComponent(Login);
    fixture.componentInstance.form.setValue({
      fullName: ' ',
      email: 'not-an-email',
      password: 'short',
    });
    await fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address.');
    expect(fixture.nativeElement.textContent).toContain('Check the password requirements');
    backend.expectNone('/api/v1/auth/register');
  });

  it('does not register twice if registration succeeds but login needs retrying', async () => {
    const component = TestBed.createComponent(Login).componentInstance;
    component.form.setValue({
      fullName: 'Buyer',
      email: 'buyer@example.com',
      password: 'Portfolio123',
    });
    const first = component.submit();
    backend.expectOne('/api/v1/auth/register').flush({ id: 'buyer' });
    await Promise.resolve();
    backend.expectOne('/api/v1/auth/login').flush({}, { status: 503, statusText: 'Unavailable' });
    await first;
    expect(component.registration()).toBe(false);
    const second = component.submit();
    backend.expectNone('/api/v1/auth/register');
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    backend
      .expectOne('/api/v1/auth/login')
      .flush({ accessToken: 'token', refreshToken: 'refresh', expiresIn: 60 });
    await second;
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/events');
  });
});
