import { render, screen } from "@testing-library/react";
import { encode } from "uqr";
import { inviteUrl, pairUrl, QrCode, qrPath } from "./QrCode";

describe("QR helpers", () => {
  it("builds the URLs documented in docs/QR_FORMAT.md", () => {
    expect(pairUrl("https://family.example.org", "K7QF-2M9X-HD4R")).toBe("https://family.example.org/pair?code=K7QF-2M9X-HD4R");
    expect(inviteUrl("http://192.168.1.10:9092", "AB CD")).toBe("http://192.168.1.10:9092/register?code=AB%20CD");
  });

  it("draws exactly one square per dark module", () => {
    const qr = encode("https://family.example.org/pair?code=K7QF-2M9X-HD4R", { ecc: "M", border: 4 });
    const dark = qr.data.flat().filter(Boolean).length;
    expect(qrPath(qr.data).match(/M/g)?.length).toBe(dark);
    // Quiet zone: the border rows/columns are light.
    expect(qr.data[0]!.some(Boolean)).toBe(false);
  });

  it("renders an accessible SVG", () => {
    render(<QrCode value="https://x.example/pair?code=A" label="QR-Code zum Verbinden" />);
    expect(screen.getByRole("img", { name: "QR-Code zum Verbinden" })).toBeInTheDocument();
  });
});
