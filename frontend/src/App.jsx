import { useEffect, useRef, useState } from "react";
import HistoryPanel from "./HistoryPanel.jsx";

const socketUrl =
  import.meta.env.VITE_GATEWAY_WS_URL ?? "ws://127.0.0.1:8081/ws";

export default function App() {
  const [count, setCount] = useState(null);
  const [status, setStatus] = useState("connecting");
  const [error, setError] = useState("");
  const [quantity, setQuantity] = useState("1");
  const socketRef = useRef(null);

  useEffect(() => {
    let active = true;
    let retryTimer;
    let socket;

    function connect() {
      setStatus("connecting");
      socket = new WebSocket(socketUrl);
      socketRef.current = socket;

      socket.addEventListener("open", () => {
        if (!active) return;
        setCount(null);
        setError("");
        setStatus("connected");
      });
      socket.addEventListener("close", () => {
        if (!active) return;
        setStatus("reconnecting");
        retryTimer = setTimeout(connect, 1000);
      });
      socket.addEventListener("error", () => {
        if (active) setError("Cannot connect to the gateway");
        socket.close();
      });
      socket.addEventListener("message", (event) => {
        if (!active) return;
        try {
          const message = JSON.parse(event.data);
          if (message.type === "count") {
            if (
              typeof message.count !== "string" ||
              !/^-?\d+$/.test(message.count)
            ) {
              throw new Error("Invalid counter value");
            }
            setCount(message.count);
            setError(
              message.status === "ok"
                ? ""
                : message.status === "division_by_zero"
                  ? "Cannot divide by zero. The count is unchanged."
                  : message.status === "overflow"
                    ? "Result is outside the whole-number range. The count is unchanged."
                    : message.status,
            );
          } else if (message.type === "error") {
            setError(message.message);
          }
        } catch {
          setError("Received an invalid message from the gateway");
        }
      });
    }

    connect();
    return () => {
      active = false;
      clearTimeout(retryTimer);
      socket.close();
      socketRef.current = null;
    };
  }, []);

  const validQuantity =
    /^[+-]?[0-9]+$/.test(quantity) &&
    BigInt(quantity) >= -(2n ** 63n) &&
    BigInt(quantity) <= 2n ** 63n - 1n;
  const ready = status === "connected" && count !== null && validQuantity;

  function send(type) {
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type, quantity }));
      setError("");
    }
  }

  return (
    <main className="page">
      <section className="counter-card" aria-labelledby="title">
        <div className="eyebrow">AERON CLUSTER · SINGLE-NODE DEMO</div>
        <h1 id="title">Cluster counter</h1>
        <p className="intro">
          Commands are sequenced by Aeron Cluster and the updated count is sent
          back over WebSocket.
        </p>

        <div className="count" aria-live="polite">
          {count === null ? "—" : count}
        </div>
        <label className="quantity-label" htmlFor="quantity">
          Quantity
        </label>
        <input
          id="quantity"
          className="quantity-input"
          type="text"
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          aria-describedby="quantity-help"
          aria-invalid={!validQuantity}
          autoComplete="off"
          spellCheck={false}
        />
        <p className="quantity-help" id="quantity-help">
          Enter a whole number, then choose an operation. Division rounds toward
          zero.
        </p>
        {!validQuantity && (
          <p className="error">
            Enter a whole number within the signed 64-bit range.
          </p>
        )}
        <div className="controls">
          {[
            ["add", "+", "Add"],
            ["subtract", "−", "Subtract"],
            ["multiply", "×", "Multiply by"],
            ["divide", "/", "Divide by"],
          ].map(([type, symbol, label]) => (
            <button
              key={type}
              type="button"
              onClick={() => send(type)}
              disabled={
                !ready || (type === "divide" && BigInt(quantity) === 0n)
              }
              aria-label={`${label} ${quantity}`}
            >
              {symbol}
              {quantity}
            </button>
          ))}
        </div>

        <div className="connection">
          <span className={`dot ${status === "connected" ? "online" : ""}`} />
          {status}
        </div>
        {error && (
          <p className="error" role="status">
            {error}
          </p>
        )}
      </section>
      <HistoryPanel liveCount={count} />
    </main>
  );
}
