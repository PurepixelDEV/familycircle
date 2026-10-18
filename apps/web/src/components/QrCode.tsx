import { encode } from "uqr";

/** QR URLs understood by the Android app (docs/QR_FORMAT.md). */
export function pairUrl(origin: string, code: string): string {
  return `${origin}/pair?code=${encodeURIComponent(code)}`;
}

export function inviteUrl(origin: string, code: string): string {
  return `${origin}/register?code=${encodeURIComponent(code)}`;
}

/** Converts the module matrix into one SVG path (one 1×1 square per dark module). */
export function qrPath(data: boolean[][]): string {
  let d = "";
  data.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (dark) d += `M${x} ${y}h1v1h-1z`;
    }),
  );
  return d;
}

/**
 * Renders a QR code as SVG. Always dark-on-white with a quiet zone, also in dark mode,
 * because many scanners cannot read inverted codes.
 */
export function QrCode({ value, label, size = 240 }: { value: string; label: string; size?: number }) {
  const qr = encode(value, { ecc: "M", border: 4 });
  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${qr.size} ${qr.size}`}
      shapeRendering="crispEdges"
      style={{ display: "block", margin: "0 auto", background: "#fff", borderRadius: 12, maxWidth: "100%", height: "auto" }}
    >
      <rect width={qr.size} height={qr.size} fill="#fff" />
      <path d={qrPath(qr.data)} fill="#000" />
    </svg>
  );
}
