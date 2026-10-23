import { useEffect, useState } from "react";

const gateway = (
  import.meta.env.VITE_GATEWAY_WS_URL ?? "ws://127.0.0.1:8081/ws"
)
  .replace(/^ws/, "http")
  .replace(/\/ws$/, "");

export default function HistoryPanel({ liveCount }) {
  const [items, setItems] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [next, setNext] = useState(null);
  const [message, setMessage] = useState("Loading history…");
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    async function load() {
      try {
        const query = new URLSearchParams({ limit: "20" });
        if (cursor) query.set("cursor", cursor);
        const response = await fetch(`${gateway}/history?${query}`, {
          signal: controller.signal,
        });
        if (!response.ok)
          throw new Error(
            "History is unavailable. Live counter controls still work.",
          );
        const page = await response.json();
        if (!active) return;
        setItems(page.items);
        setNext(page.nextCursor);
        setMessage(
          page.state === "caught_up"
            ? "Showing recorded history. New live results may take a moment to appear."
            : `History ${page.state.replaceAll("_", " ")}. ${page.detail}`,
        );
      } catch (error) {
        if (active) setMessage(error.message);
      }
    }
    load();
    const timer = setInterval(load, 3000);
    return () => {
      active = false;
      controller.abort();
      clearInterval(timer);
    };
  }, [cursor, revision, liveCount]);

  return (
    <section className="history-card" aria-labelledby="history-title">
      <div className="history-header">
        <h2 id="history-title">Operation history</h2>
        <button
          onClick={() => {
            setCursor(null);
            setRevision((value) => value + 1);
          }}
        >
          Latest / refresh
        </button>
      </div>
      <p role="status">{message}</p>
      <div className="history-table">
        <table>
          <thead>
            <tr>
              <th>Incoming operation</th>
              <th>Quantity</th>
              <th>Capture</th>
              <th>Outgoing result</th>
              <th>Status</th>
              <th>Source / command position</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={`${item.sourceId}:${item.commandPosition}`}>
                <td>{item.operation}</td>
                <td>{item.quantity ?? "—"}</td>
                <td>{item.captureKind ?? "UNCLASSIFIED"}</td>
                <td>{item.count}</td>
                <td>{item.status}</td>
                <td>
                  <code title={item.sourceId}>
                    {item.sourceId.slice(0, 8)}… / {item.commandPosition}
                  </code>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {items.length === 0 && <p>No recorded operations yet.</p>}
      <button disabled={!next} onClick={() => setCursor(next)}>
        Older operations
      </button>
    </section>
  );
}
