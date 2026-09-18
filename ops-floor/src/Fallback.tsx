import { useStore } from './store';

// Real HTML table fallback of the same data when WebGL is unavailable —
// not an error screen.
export function Fallback() {
  const snapshot = useStore((s) => s.snapshot);
  if (!snapshot) return null;
  return (
    <div className="fallback">
      <h1>FulfillOps — Ops Floor (no WebGL — table fallback)</h1>
      <h2>Stock (seller inventory)</h2>
      <table>
        <thead>
          <tr><th>SKU</th><th>Name</th><th>Stock</th><th>Unit price</th></tr>
        </thead>
        <tbody>
          {snapshot.stocks.map((s) => (
            <tr key={s.sku}>
              <td className="mono">{s.sku}</td>
              <td>{s.name}</td>
              <td>{s.stock}</td>
              <td>{s.unitPrice.toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>Seller orders (Angular, in-memory)</h2>
      <table>
        <thead>
          <tr><th>Order</th><th>Customer</th><th>SKU</th><th>Qty</th><th>Total</th><th>Status</th></tr>
        </thead>
        <tbody>
          {snapshot.sellerOrders.map((o) => (
            <tr key={o.id}>
              <td className="mono">{o.id}</td>
              <td>{o.customer}</td>
              <td className="mono">{o.sku}</td>
              <td>{o.quantity}</td>
              <td>{o.total.toFixed(2)}</td>
              <td>{o.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>CS orders (Spring, H2)</h2>
      <table>
        <thead>
          <tr><th>Order</th><th>Customer</th><th>SKU</th><th>Qty</th><th>Total</th><th>Status</th></tr>
        </thead>
        <tbody>
          {snapshot.csOrders.map((o) => (
            <tr key={o.id}>
              <td className="mono">{o.orderNumber}</td>
              <td>{o.customer}</td>
              <td className="mono">{o.sku}</td>
              <td>{o.quantity}</td>
              <td>{o.total.toFixed(2)}</td>
              <td>{o.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
