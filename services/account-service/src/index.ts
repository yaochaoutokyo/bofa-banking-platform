import { randomUUID } from "node:crypto";
import { createApp } from "./app";
import { systemClock } from "./domain";
import { InMemoryAccountRepository } from "./repositories/accountRepository";
import { AccountService, AccountEventPublisher } from "./services/accountService";

const consolePublisher: AccountEventPublisher = {
  async publish(event) {
    console.log(JSON.stringify(event));
  },
};

const service = new AccountService(
  new InMemoryAccountRepository(),
  consolePublisher,
  systemClock,
  { next: () => `ACC-${randomUUID().slice(0, 8).toUpperCase()}` },
);

const port = Number(process.env.PORT ?? 8202);
createApp(service).listen(port, () => {
  console.log(`account-service listening on ${port}`);
});
