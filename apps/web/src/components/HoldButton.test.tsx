import { act, fireEvent, render, screen } from "@testing-library/react";
import { HoldButton } from "./HoldButton";

function setup() {
  const onComplete = vi.fn();
  render(
    <HoldButton label="SOS auslösen" help="3 Sekunden halten" countdownText={(n) => `Noch ${n} Sekunden`} releasedText="Losgelassen" onComplete={onComplete}>
      SOS
    </HoldButton>,
  );
  return { onComplete, button: screen.getByRole("button", { name: "SOS auslösen" }) };
}

describe("HoldButton", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("does not fire before 3 seconds and fires after", () => {
    const { onComplete, button } = setup();
    fireEvent.pointerDown(button, { button: 0, pointerId: 1 });
    act(() => vi.advanceTimersByTime(2900));
    expect(onComplete).not.toHaveBeenCalled();
    expect(button).toHaveTextContent("1"); // visible countdown
    act(() => vi.advanceTimersByTime(100));
    expect(onComplete).toHaveBeenCalledOnce();
    expect(button).toHaveTextContent("SOS");
  });

  it("shows the countdown and cancels when released early", () => {
    const { onComplete, button } = setup();
    fireEvent.pointerDown(button, { button: 0, pointerId: 1 });
    expect(button).toHaveTextContent("3");
    expect(screen.getByRole("status")).toHaveTextContent("Noch 3 Sekunden");
    act(() => vi.advanceTimersByTime(1500));
    expect(button).toHaveTextContent("2");
    fireEvent.pointerUp(button, { button: 0, pointerId: 1 });
    act(() => vi.advanceTimersByTime(5000));
    expect(onComplete).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("Losgelassen");
  });

  it("ignores a plain click", () => {
    const { onComplete, button } = setup();
    fireEvent.click(button);
    act(() => vi.advanceTimersByTime(5000));
    expect(onComplete).not.toHaveBeenCalled();
  });

  it("works with the keyboard: hold Space, auto-repeat does not restart the timer", () => {
    const { onComplete, button } = setup();
    fireEvent.keyDown(button, { key: " " });
    act(() => vi.advanceTimersByTime(2000));
    fireEvent.keyDown(button, { key: " ", repeat: true });
    act(() => vi.advanceTimersByTime(999));
    expect(onComplete).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onComplete).toHaveBeenCalledOnce();
  });

  it("cancels when Enter is released early", () => {
    const { onComplete, button } = setup();
    fireEvent.keyDown(button, { key: "Enter" });
    act(() => vi.advanceTimersByTime(1000));
    fireEvent.keyUp(button, { key: "Enter" });
    act(() => vi.advanceTimersByTime(5000));
    expect(onComplete).not.toHaveBeenCalled();
  });
});
