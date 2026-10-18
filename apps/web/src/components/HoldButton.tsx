import { useEffect, useId, useRef, useState, type ReactNode } from "react";

type Props = {
  /** Accessible name of the button. */
  label: string;
  /** Explains how to use it (referenced by aria-describedby). */
  help: string;
  /** Status text while holding, e.g. "Keep holding for {n} seconds". */
  countdownText: (secondsLeft: number) => string;
  /** Announced when released too early. */
  releasedText: string;
  durationMs?: number;
  disabled?: boolean;
  className?: string;
  onComplete: () => void;
  children: ReactNode;
};

const TICK_MS = 100;

/**
 * A button that fires only after being held for `durationMs` (pointer, touch, or Space/Enter held
 * down). Releasing earlier cancels. Prevents accidental alarms; the progress ring and the spoken
 * countdown make the waiting visible.
 */
export function HoldButton({ label, help, countdownText, releasedText, durationMs = 3000, disabled, className, onComplete, children }: Props) {
  const helpId = useId();
  const [elapsed, setElapsed] = useState<number | null>(null);
  const [status, setStatus] = useState("");
  const timers = useRef<{ tick?: ReturnType<typeof setInterval>; done?: ReturnType<typeof setTimeout>; start: number }>({ start: 0 });

  const clear = () => {
    clearInterval(timers.current.tick);
    clearTimeout(timers.current.done);
    timers.current.tick = undefined;
    timers.current.done = undefined;
  };
  useEffect(() => clear, []);

  const start = () => {
    if (disabled || timers.current.done) return;
    timers.current.start = Date.now();
    setElapsed(0);
    setStatus(countdownText(Math.ceil(durationMs / 1000)));
    let lastSecond = Math.ceil(durationMs / 1000);
    timers.current.tick = setInterval(() => {
      const e = Math.min(durationMs, Date.now() - timers.current.start);
      setElapsed(e);
      const left = Math.ceil((durationMs - e) / 1000);
      if (left !== lastSecond && left > 0) {
        lastSecond = left;
        setStatus(countdownText(left));
      }
    }, TICK_MS);
    timers.current.done = setTimeout(() => {
      clear();
      setElapsed(null);
      setStatus("");
      onComplete();
    }, durationMs);
  };

  const cancel = () => {
    if (!timers.current.done) return;
    clear();
    setElapsed(null);
    setStatus(releasedText);
  };

  const progress = elapsed === null ? 0 : elapsed / durationMs;
  const secondsLeft = elapsed === null ? null : Math.max(1, Math.ceil((durationMs - elapsed) / 1000));

  return (
    <>
      <button
        type="button"
        className={"hold-btn" + (elapsed !== null ? " holding" : "") + (className ? " " + className : "")}
        aria-label={label}
        aria-describedby={helpId}
        disabled={disabled}
        style={{ ["--progress" as string]: progress }}
        onPointerDown={(e) => {
          if (e.button !== 0) return;
          try {
            e.currentTarget.setPointerCapture(e.pointerId);
          } catch {
            // not supported (e.g. synthetic events)
          }
          start();
        }}
        onPointerUp={cancel}
        onPointerCancel={cancel}
        onLostPointerCapture={cancel}
        onBlur={cancel}
        onKeyDown={(e) => {
          if (e.key !== " " && e.key !== "Enter") return;
          e.preventDefault(); // no click: only holding counts
          if (!e.repeat) start();
        }}
        onKeyUp={(e) => {
          if (e.key !== " " && e.key !== "Enter") return;
          e.preventDefault();
          cancel();
        }}
        onClick={(e) => e.preventDefault()}
        onContextMenu={(e) => e.preventDefault()} // long press on touch screens
      >
        <svg className="hold-ring" viewBox="0 0 100 100" aria-hidden="true">
          <circle cx="50" cy="50" r="46" pathLength={100} />
        </svg>
        <span className="hold-label" aria-hidden="true">
          {secondsLeft ?? children}
        </span>
      </button>
      <span id={helpId} className="visually-hidden">
        {help}
      </span>
      <span className="visually-hidden" role="status" aria-live="assertive">
        {status}
      </span>
    </>
  );
}
