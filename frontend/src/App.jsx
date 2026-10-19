import { useEffect, useRef, useState } from "react";

const socketUrl = import.meta.env.VITE_GATEWAY_WS_URL ?? "ws://127.0.0.1:8081/ws";

export default function App() {
  const [count, setCount] = useState(null);
  const [status, setStatus] = useState("connecting");
  const [error, setError] = useState("");
  const socketRef = useRef(null);

  useEffect(() => {
    const socket = new WebSocket(socketUrl);
    socketRef.current = socket;

    socket.addEventListener("open", () => {
      setStatus("connected");
      socket.send(JSON.stringify({ type: "get" }));
    });
    socket.addEventListener("close", () => setStatus("disconnected"));
    socket.addEventListener("error", () => setStatus("connection error"));
    socket.addEventListener("message", (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === "count") {
          setCount(message.count);
          setError(message.status === "ok" ? "" : message.status);
        } else if (message.type === "error") {
          setError(message.message);
        }
      } catch {
        setError("Received an invalid message from the gateway");
      }
    });

    return () => {
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
        <div className="eyebrow">AERON CLUSTER · REPLICATED STATE</div>
        <h1 id="title">Cluster counter</h1>
        <p className="intro">Commands are sequenced by Aeron Cluster and the updated count is sent back over WebSocket.</p>

        <div className="count" aria-live="polite">{count === null ? "—" : count}</div>
        <div className="controls">
          <button type="button" onClick={() => send("decrement")} disabled={status !== "connected"} aria-label="Decrement count">
            −1
          </button>
          <button type="button" onClick={() => send("increment")} disabled={status !== "connected"} aria-label="Increment count">
            +1
          </button>
        </div>

        <div className="connection"><span className={`dot ${status === "connected" ? "online" : ""}`} />{status}</div>
        {error && <p className="error" role="status">{error}</p>}
      </section>
    </main>
  );
}
