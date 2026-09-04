/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  testMatch: ["<rootDir>/tests/**/*.test.ts"],
  collectCoverage: true,
  collectCoverageFrom: ["src/**/*.ts", "!src/index.ts"],
  coverageDirectory: "coverage",
  coverageReporters: ["text", "text-summary", "lcov", "json-summary"],
  coverageThreshold: {
    global: { lines: 80, branches: 70, functions: 80, statements: 80 },
  },
  clearMocks: true,
};
