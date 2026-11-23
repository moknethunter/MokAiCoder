# MokAiCoder for VS Code

The desktop extension connects directly to the xAI API. It can answer a coding
question or review the active local file or selected text.

1. In VS Code, open **Extensions**, then choose **Install from VSIX…**.
2. Open the Command Palette and run **MokAiCoder: Set Grok API Key**. Paste your
   xAI API key once. The extension stores it in VS Code SecretStorage.
3. Run **MokAiCoder: Ask Grok** or **MokAiCoder: Review Active File or Selection**.
   Review opens the answer in a separate Markdown editor.
4. Set **MokAiCoder › Grok Model** if you prefer to choose a specific model.
   The **MokAiCoder: Remove Grok API Key** command clears the saved key.

You can open a GitHub checkout with VS Code's built-in **Git: Clone** command and
review its local files the same way. The extension uses the selected text, or the
active file if there is no selection. It asks before sending code or a question
to xAI. API usage may incur charges.
