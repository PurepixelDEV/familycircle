import { de } from "./de";
import { en } from "./en";
import { format } from "./index";

describe("i18n", () => {
  it("has a non-empty English text for every German key with the same placeholders", () => {
    for (const key of Object.keys(de) as (keyof typeof de)[]) {
      expect(en[key], key).toBeTruthy();
      const vars = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
      expect(vars(en[key]), key).toEqual(vars(de[key]));
    }
  });

  it("formats placeholders", () => {
    expect(format("Akku {p} %", { p: 40 })).toBe("Akku 40 %");
    expect(format("{missing}", {})).toBe("{missing}");
  });
});
