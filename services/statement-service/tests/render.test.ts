import { formatMinor, maskAccount } from "../src/services/render";

describe("formatMinor", () => {
  it.each([
    [0, "$0.00"],
    [1_37, "$1.37"],
    [1_234_567_89, "$1,234,567.89"],
    [-84_12, "-$84.12"],
  ])("formats %d as %s", (minor, expected) => {
    expect(formatMinor(minor)).toBe(expected);
  });
});

describe("maskAccount", () => {
  it("keeps the last four characters", () => {
    expect(maskAccount("ACC-1001")).toBe("****1001");
  });
});
