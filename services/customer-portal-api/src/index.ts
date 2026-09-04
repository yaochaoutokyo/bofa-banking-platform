import { createApp } from "./app";

const port = Number(process.env.PORT ?? 8201);
createApp().listen(port, () => {
  console.log(`customer-portal-api listening on ${port}`);
});
