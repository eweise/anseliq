import { defineConfig } from 'vite';
import path from 'path';

export default defineConfig(({ command }) => {
  return {
    resolve: {
      alias: {
        // Where build.sbt has Scala.js write the linked JS (scalaJSLinkerOutputDirectory).
        "scalajs": command === "serve" ? `./target/frontend-fastopt/main.js` : `./target/frontend-opt/main.js`,
        "resources": path.resolve(__dirname, "./src/main/resources"),
        "js": path.resolve(__dirname, "./src/main/js"),
      }
    },
    server: {
      port: 9876,
      historyApiFallback: true,
      cors: true,
      // The frontend calls the API on its own origin (see HttpPath), so pass it to the backend.
      proxy: {
        '/api': 'http://localhost:8080',
      },
    },
  };
});
