/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  testMatch: ["<rootDir>/tests/**/*.test.ts"],
  collectCoverage: true,
  collectCoverageFrom: ["src/**/*.ts", "!src/index.ts"],
  coverageDirectory: "coverage",
  coverageReporters: ["text", "text-summary", "lcov", "json-summary"],
  // Ratchet: first step of the gradual rollout. Raise toward the 90/80
  // compliance-critical target as each KYC control gains tests.
  coverageThreshold: {
    global: { lines: 25, branches: 25, functions: 15, statements: 25 },
  },
  clearMocks: true,
};
