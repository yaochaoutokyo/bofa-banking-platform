import { createApp } from "./app";

const port = Number(process.env.PORT ?? 8204);
createApp().listen(port, () => {
  console.log(`kyc-service listening on ${port}`);
});
