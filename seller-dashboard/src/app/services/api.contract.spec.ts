// Consumer side of the seller-dashboard -> order-service and -> inventory-service
// contracts. Each test drives the app's real services against a Pact mock server;
// the recorded pact files are verified against the real services in their builds.
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatchersV3, PactV4 } from '@pact-foundation/pact';
import path from 'node:path';
import { firstValueFrom } from 'rxjs';
import { API_BASE_URL, DEMO_MODE } from '../api';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { InventoryService } from './inventory.service';
import { OrdersService } from './orders.service';

const { eachLike, like, regex, integer, decimal } = MatchersV3;

const dir = path.resolve(process.cwd(), '../contracts/pacts');
const orderService = new PactV4({
  consumer: 'seller-dashboard',
  provider: 'order-service',
  dir,
  logLevel: 'warn',
});
const inventoryService = new PactV4({
  consumer: 'seller-dashboard',
  provider: 'inventory-service',
  dir,
  logLevel: 'warn',
});

const BEARER = { Authorization: regex('^Bearer .+$', 'Bearer token') };

/** Real services and interceptor; only the token source is stubbed for the signed-in cases. */
function configure(baseUrl: string, signedIn: boolean): void {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
      provideRouter([]),
      { provide: API_BASE_URL, useValue: baseUrl },
      { provide: DEMO_MODE, useValue: false },
      ...(signedIn
        ? [{ provide: AuthService, useValue: { token: () => 'token', signOut: () => undefined } }]
        : []),
    ],
  });
}

const product = (sku: string, stock: number) => ({
  id: like(sku),
  sku: like(sku),
  name: like('Wireless Mouse'),
  unitPrice: decimal(24.99),
  stock: integer(stock),
});

describe('seller-dashboard -> order-service', () => {
  it('signs a seller in', () =>
    orderService
      .addInteraction()
      .given('account seller with password seller-password and role SELLER')
      .uponReceiving('a seller signs in')
      .withRequest('POST', '/api/auth/token', (b) =>
        b.jsonBody({ username: 'seller', password: 'seller-password' }),
      )
      .willRespondWith(200, (b) =>
        b.jsonBody({ accessToken: like('eyJhbGciOiJIUzI1NiJ9.e30.sig'), roles: ['SELLER'] }),
      )
      .executeTest(async (server) => {
        configure(server.url, false);
        const auth = TestBed.inject(AuthService);
        await auth.signIn('seller', 'seller-password');
        expect(auth.isSignedIn()).toBe(true);
      }));

  it('rejects a wrong password', () =>
    orderService
      .addInteraction()
      .given('account seller with password seller-password and role SELLER')
      .uponReceiving('a seller sign-in with the wrong password')
      .withRequest('POST', '/api/auth/token', (b) =>
        b.jsonBody({ username: 'seller', password: 'wrong' }),
      )
      .willRespondWith(401)
      .executeTest(async (server) => {
        configure(server.url, false);
        const auth = TestBed.inject(AuthService);
        await expect(auth.signIn('seller', 'wrong')).rejects.toMatchObject({ status: 401 });
        expect(auth.isSignedIn()).toBe(false);
      }));

  it('lists orders for the seller', () =>
    orderService
      .addInteraction()
      .given('demo orders exist')
      .uponReceiving('a request for orders by a seller')
      .withRequest('GET', '/api/orders', (b) => b.headers(BEARER))
      .willRespondWith(200, (b) =>
        b.jsonBody(
          eachLike({
            orderNumber: like('ORD-1048'),
            customerName: like('Priya Nair'),
            sku: like('SKU-1003'),
            quantity: integer(2),
            total: decimal(90.0),
            status: regex('^(PENDING|PROCESSING|SHIPPED|DELIVERED|REFUNDED)$', 'PENDING'),
          }),
        ),
      )
      .executeTest(async (server) => {
        configure(server.url, true);
        const orders = TestBed.inject(OrdersService);
        await orders.load();
        const [first] = await firstValueFrom(orders.orders$);
        expect(first).toMatchObject({ id: 'ORD-1048', customer: 'Priya Nair', status: 'PENDING' });
      }));
});

describe('seller-dashboard -> inventory-service', () => {
  it('lists products', () =>
    inventoryService
      .addInteraction()
      .given('product SKU-1001 exists with 142 units on hand')
      .uponReceiving('a request for the catalog')
      .withRequest('GET', '/api/products', (b) => b.headers(BEARER))
      .willRespondWith(200, (b) => b.jsonBody(eachLike(product('SKU-1001', 142))))
      .executeTest(async (server) => {
        configure(server.url, true);
        const inventory = TestBed.inject(InventoryService);
        await inventory.load();
        expect(inventory.hasSku('sku-1001')).toBe(true);
      }));

  it('adds a product', () =>
    inventoryService
      .addInteraction()
      .given('no product SKU-TEST-1 exists')
      .uponReceiving('a request to add product SKU-TEST-1')
      .withRequest('POST', '/api/products', (b) =>
        b
          .headers(BEARER)
          .jsonBody({ sku: 'SKU-TEST-1', name: 'Test Lamp', unitPrice: 12.5, stock: 9 }),
      )
      .willRespondWith(201, (b) =>
        b.jsonBody({
          id: 'SKU-TEST-1',
          sku: 'SKU-TEST-1',
          name: 'Test Lamp',
          unitPrice: decimal(12.5),
          stock: 9,
        }),
      )
      .executeTest(async (server) => {
        configure(server.url, true);
        const inventory = TestBed.inject(InventoryService);
        await inventory.add({ sku: 'SKU-TEST-1', name: 'Test Lamp', unitPrice: 12.5, stock: 9 });
        expect(inventory.hasSku('SKU-TEST-1')).toBe(true);
      }));

  it('refuses a duplicate SKU', () =>
    inventoryService
      .addInteraction()
      .given('product SKU-1001 exists with 142 units on hand')
      .uponReceiving('a request to add a SKU that already exists')
      .withRequest('POST', '/api/products', (b) =>
        b
          .headers(BEARER)
          .jsonBody({ sku: 'SKU-1001', name: 'Another Mouse', unitPrice: 20, stock: 1 }),
      )
      .willRespondWith(409)
      .executeTest(async (server) => {
        configure(server.url, true);
        const inventory = TestBed.inject(InventoryService);
        await expect(
          inventory.add({ sku: 'SKU-1001', name: 'Another Mouse', unitPrice: 20, stock: 1 }),
        ).rejects.toMatchObject({ status: 409 });
      }));

  it('updates stock', () =>
    inventoryService
      .addInteraction()
      .given('product SKU-1001 exists with 142 units on hand')
      .uponReceiving('a stock update for SKU-1001')
      .withRequest('PUT', '/api/products/SKU-1001/stock', (b) =>
        b.headers(BEARER).jsonBody({ stock: 33 }),
      )
      .willRespondWith(200, (b) => b.jsonBody({ ...product('SKU-1001', 33), stock: 33 }))
      .executeTest(async (server) => {
        configure(server.url, true);
        const inventory = TestBed.inject(InventoryService);
        await expect(inventory.updateStock('SKU-1001', 33)).resolves.toBeUndefined();
      }));
});
