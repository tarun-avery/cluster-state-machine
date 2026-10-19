import { useEffect, useRef, useState } from "react";

const socketUrl =
  import.meta.env.VITE_GATEWAY_WS_URL ?? "ws://127.0.0.1:8081/ws";

export default function App() {
  const [count, setCount] = useState(null);
  const [status, setStatus] = useState("connecting");
  const [error, setError] = useState("");
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
            setError(message.status === "ok" ? "" : message.status);
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

  function send(type) {
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type }));
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
        <div className="controls">
          <button
            type="button"
            onClick={() => send("decrement")}
            disabled={status !== "connected" || count === null}
            aria-label="Decrement count"
          >
            −1
          </button>
          <button
            type="button"
            onClick={() => send("increment")}
            disabled={status !== "connected" || count === null}
            aria-label="Increment count"
          >
            +1
          </button>
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
    </main>
  );
}
