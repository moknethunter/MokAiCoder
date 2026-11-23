# MokAiCoder

MokAiCoder is a project assistant for GitHub repositories, with an Arabic web interface and two Android editions. The server supports Grok, OpenRouter, Ollama, and a configurable OpenAI-compatible provider.

[Arabic help guide](docs/help.ar.md) · [Android build instructions](android/README.md) · [GitHub Actions builds](https://github.com/moknethunter/MokAiCoder/actions)

## Features

- **Project assistant.** Reviews eligible text files from a fixed GitHub revision in batches, uses Grok to propose coordinated changes, and presents a preview. An approved proposal can be published as one commit on a new branch.
- **Project scanner.** Maps dependencies and reports cycles, unresolved local imports, isolated files, and duplicate symbols.
- **Android voice and documents edition.** Supports local dictation with an imported Vosk model, Android's local text-to-speech voices, and document previews with Word and PDF export.
- **Full Android edition.** Adds chat, access to the hosted project assistant, and local GGUF text models on ARM64 when built with the bundled llama.cpp runtime.

## Run the server

Copy `.env.example` to `.env`. Set a private `MOKAI_CHAT_TOKEN` with at least 24 characters. To use Grok with a GitHub repository, configure `XAI_API_KEY`, `GITHUB_OWNER`, `GITHUB_REPO`, and `GITHUB_BRANCH`. Provide `GITHUB_TOKEN` for authenticated reads and a repository-scoped `MOKAI_GITHUB_WRITE_TOKEN` with Contents write permission to publish new branches.

With Node.js 24:

```sh
cp .env.example .env
npm ci
npm run build
node --env-file=.env dist/index.js
```

With Docker Compose, configure the same `.env` file and run:

```sh
docker compose up -d --build
```

Open `http://127.0.0.1:8080/` for the scanner, `/chat` for chat, or `/project` for the Grok project assistant. Use an HTTPS server address for Android access. Keep provider keys on the server and enter the MokAiCoder access token on the client.

## Review and publish a project

1. Open **Project Assistant** (`/project`), then select a Grok model and enter the task.
2. Choose project review or a coordinated edit proposal. The workflow reviews up to 36 batches of eligible files. Each edit proposal covers up to eight files.
3. Check the report and compare the proposed contents before publishing. Publishing creates a separate branch. Use the comparison page to open a pull request.
4. Run the target repository's CI to verify behavior. MokAiCoder checks dependencies through static analysis and includes the results with the proposal.

## Development checks

```sh
npm run lint
npm test
```

The GitHub workflows build and test the Node.js service, verify the Docker image, and assemble the Android preview APKs.


## Direct Android and VS Code connections

In the full Android edition, choose **Grok مباشر** to use xAI from the phone.
The app stores connection credentials with Android Keystore encryption.
The **GitHub** screen can read public repositories directly. For a private
repository, use a fine-grained read-only token or install a GitHub App with
Contents read-only permission and enable its device flow. The Android client
takes the app's public Client ID and handles user authorization and token refresh.

The [desktop extension](vscode/README.md) runs against the xAI API from VS Code.
See the [Arabic guide](docs/help.ar.md) for the phone setup.
