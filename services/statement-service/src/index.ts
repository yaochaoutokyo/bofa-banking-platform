import { createApp } from "./app";

const port = Number(process.env.PORT ?? 8203);
createApp().listen(port, () => {
  console.log(`statement-service listening on ${port}`);
});
