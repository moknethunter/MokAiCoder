const get = (id) => document.getElementById(id);
const unlock = get('unlock');
const tokenField = get('chat-token');
const room = get('chat-room');
const status = get('chat-status');
const form = get('chat-form');
const providerField = get('chat-provider');
const modelField = get('chat-model');
const fileField = get('chat-file');
const messageField = get('chat-message');
const transcript = get('chat-messages');
const send = get('chat-send');
const stop = get('chat-stop');
const share = get('chat-share');
const paid = get('chat-paid');
const reset = get('chat-reset');
const lock = get('chat-lock');
const loadFiles = get('chat-load-files');

const reviewerField = get('chat-reviewer');
const reviewModel = get('chat-review-model');
const reviewShare = get('chat-review-share');
const workspaceForm = get('workspace-form');
const workspaceSource = get('workspace-source');
const workspacePath = get('workspace-path');
const workspaceClose = get('workspace-close');
const workspacePublish = get('workspace-publish');
const workspaceAfter = get('workspace-after');
const workspaceCompare = get('workspace-compare');

let accessToken = '';
let providers = [];
let turns = [];
let scanId = '';
let request;
let catalogRequest;
let reviewerRequest;
let capabilities;
let workspaceFile;
let workspaceBusy = false;
let proposalReady = false;

const tiers = { free: 'سحابي مجاني', local: 'محلي', paid: 'سحابي مدفوع' };
const messages = {
  AI_SETUP_REQUIRED:
    'اضبط رمز المحادثة ومزوّدًا واحدًا على الأقل في إعدادات الخادم.',
  AI_UNAUTHORIZED: 'تحقّق من رمز وصول MokAiCoder.',
  AI_RATE_LIMIT: 'بلغت حدّ الاستخدام. حاول لاحقًا.',
  AI_BUSY: 'النظام يجهّز ردودًا أخرى الآن. حاول بعد قليل.',
  AI_TIMEOUT: 'انتهت مهلة انتظار النموذج.',
  AI_PROVIDER_AUTH: 'رفض المزوّد بيانات الاعتماد المهيأة على الخادم.',
  AI_PROVIDER_CREDITS: 'يتطلب المزوّد رصيد API لهذا الطلب.',
  AI_REVIEW_CONSENT: 'أكّد موافقتك على مشاركة المحادثة والمراجعة.',
  AI_REVIEW_PROVIDER: 'اختر مزوّدًا مختلفًا للمراجعة.',
  AI_HANDOFF_LIMIT: 'تجاوزت المسودة أو المراجعة الحجم المسموح.',
  WORKSPACE_CHANGED: 'تغيّر الملف أو الفرع. أعد فتح الملف قبل المتابعة.',
  WORKSPACE_SENSITIVE: 'يحتوي الملف على أسرار. أزلها قبل إرساله للنموذج.',
  WORKSPACE_UNAVAILABLE: 'تعذّر فتح هذا المسار في المشروع المحدد.',
  WORKSPACE_TEXT_REQUIRED: 'اختر ملفًا نصيًا بترميز UTF-8 حتى 8000 محرف.',
  WORKSPACE_WRITE_DISABLED: 'الرفع معطّل حتى تضبط اعتماد الكتابة لـ GitHub.',
  WORKSPACE_GITHUB_AUTH: 'رفض GitHub اعتماد الخادم أو صلاحية الكتابة.',
  WORKSPACE_GITHUB_ERROR: 'تعذّر إكمال العملية عبر GitHub.',
  WORKSPACE_SAVE_FAILED: 'تعذّر الحفظ على الجهاز.',
  WORKSPACE_NO_CHANGE: 'محتوى الملف المقترح مطابق للملف الأصلي.',
  INVALID_REQUEST: 'تحقّق من قيم الطلب أو صيغة مسار الملف.',
  AI_PROVIDER_ERROR: 'تعذّر الاتصال بالمزوّد أو إكمال الرد.',
  AI_INVALID_STREAM: 'أعاد المزوّد استجابة غير مدعومة.',
  AI_INCOMPLETE: 'انقطع الرد قبل الاكتمال.',
  AI_RESPONSE_LIMIT: 'تجاوز الرد الحجم المسموح.',
  AI_MODEL_UNAVAILABLE: 'هذا النموذج غير متاح لدى المزوّد.',
  AI_PAID_CONSENT: 'أكّد موافقتك على تكلفة الطلب.',
  AI_FILE_CONSENT: 'أكّد مشاركة الملف مع المزوّد السحابي.',
  FILE_NOT_FOUND: 'هذا الملف غير متاح للمحادثة.',
  SCAN_REQUIRED: 'شغّل فحص المشروع أولًا.',
  SCAN_CHANGED: 'تغيّرت نتيجة الفحص. أعد تحميل قائمة الملفات.',
};

function feedback(text, error = false) {
  status.textContent = text;
  status.dataset.state = error ? 'error' : '';
  status.hidden = !text;
}

function errorText(code, retryAfter) {
  const text = Object.hasOwn(messages, code)
    ? messages[code]
    : 'تعذّر إكمال الطلب.';
  return retryAfter && /^\d+$/.test(retryAfter)
    ? `${text} الانتظار المقترح ${retryAfter} ثانية.`
    : text;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...options.headers, Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(errorText(error.code, response.headers.get('Retry-After')));
  }
  return response;
}

function activeProvider() {
  return providers.find((provider) => provider.id === providerField.value);
}

function syncControls() {
  const selected = providers.filter(({ id }) =>
    [providerField.value, reviewerField.value].includes(id),
  );
  const cloud = selected.some(({ tier }) => tier !== 'local');
  get('chat-share-label').hidden =
    !(fileField.value || workspaceFile) || !cloud;
  get('chat-review-share-label').hidden = !reviewerField.value || !cloud;
  get('chat-paid-label').hidden = !selected.some(({ tier }) => tier === 'paid');
  share.required = !get('chat-share-label').hidden;
  paid.required = !get('chat-paid-label').hidden;
  reviewShare.required = !get('chat-review-share-label').hidden;
  send.disabled = Boolean(
    request ||
    workspaceBusy ||
    !modelField.value ||
    (reviewerField.value && !reviewModel.value),
  );
  const locked = Boolean(request || workspaceBusy);
  for (const field of [
    workspaceSource,
    workspacePath,
    workspaceClose,
    get('workspace-open'),
  ])
    field.disabled = locked;
  workspaceAfter.readOnly = locked;
  workspacePublish.disabled =
    locked ||
    !proposalReady ||
    (workspaceFile?.source === 'github' && !capabilities?.github?.publish);
}

function clearConsents() {
  share.checked = paid.checked = reviewShare.checked = false;
}

function syncReviewers() {
  const previous = reviewerField.value;
  const available = providers.filter(({ id }) => id !== providerField.value);
  reviewerField.replaceChildren(
    new Option('دون مراجع', ''),
    ...available.map(({ id, label }) => new Option(label, id)),
  );
  if (available.some(({ id }) => id === previous))
    reviewerField.value = previous;
  loadReviewerModels();
}

async function loadReviewerModels() {
  reviewerRequest?.abort();
  const loading = new AbortController();
  reviewerRequest = loading;
  const id = reviewerField.value;
  get('chat-review-model-field').hidden = !id;
  reviewModel.replaceChildren();
  clearConsents();
  syncControls();
  if (!id) return;
  try {
    const response = await api(`/api/ai/providers/${id}/models`, {
      signal: loading.signal,
    });
    const models = await response.json();
    if (loading.signal.aborted) return;
    reviewModel.replaceChildren(
      ...models.map(({ id, label }) => new Option(label, id)),
    );
    if (!models.length) feedback('لم يُرجع المزوّد المراجع نماذج متاحة.', true);
  } catch (error) {
    if (!loading.signal.aborted) feedback(error.message, true);
  } finally {
    if (reviewerRequest === loading) {
      reviewerRequest = undefined;
      syncControls();
    }
  }
}

function resetWorkspace() {
  workspaceFile = undefined;
  proposalReady = false;
  get('workspace-preview').hidden = true;
  workspaceClose.hidden = true;
  workspaceAfter.value = '';
  workspaceCompare.hidden = true;
  workspaceCompare.removeAttribute('href');
  clearConsents();
  syncControls();
}

async function loadWorkspace() {
  const currentToken = accessToken;
  const response = await api('/api/ai/workspace');
  const configuration = await response.json();
  if (currentToken !== accessToken || room.hidden) return;
  capabilities = configuration;
  const options = [];
  if (capabilities.github) {
    const { repository, branch } = capabilities.github;
    options.push(new Option(`GitHub · ${repository} · ${branch}`, 'github'));
  }
  if (capabilities.local)
    options.push(new Option('مجلد المشروع على جهاز الخادم', 'local'));
  workspaceSource.replaceChildren(...options);
  get('chat-workspace').hidden = !options.length;
}

async function workspacePost(path, payload) {
  const response = await api('/api/ai/workspace/' + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  return response.json();
}

workspaceForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (request || workspaceBusy) return;
  resetWorkspace();
  const selection = {
    source: workspaceSource.value,
    path: workspacePath.value.trim(),
  };
  workspaceBusy = true;
  syncControls();
  try {
    const snapshot = await workspacePost('open', selection);
    if (!accessToken || room.hidden) return;
    workspaceFile = { ...selection, revision: snapshot.revision };
    fileField.value = '';
    get('workspace-before').textContent = snapshot.content;
    get('workspace-preview').hidden = false;
    workspaceClose.hidden = false;
    workspacePublish.textContent =
      selection.source === 'github'
        ? 'رفع المحتوى المعروض إلى فرع GitHub'
        : 'حفظ المحتوى المعروض على الجهاز';
    feedback(
      snapshot.exists
        ? 'صف التعديل المطلوب في المحادثة.'
        : 'صف محتوى الملف الجديد في المحادثة.',
    );
    messageField.focus();
  } catch (error) {
    if (accessToken) feedback(error.message, true);
  } finally {
    workspaceBusy = false;
    syncControls();
  }
});

workspacePath.addEventListener('input', resetWorkspace);
workspaceSource.addEventListener('change', resetWorkspace);
workspaceClose.addEventListener('click', resetWorkspace);
workspaceAfter.addEventListener('input', () => {
  if (!workspaceFile) return;
  proposalReady =
    Boolean(workspaceAfter.value) &&
    workspaceAfter.value !== get('workspace-before').textContent;
  syncControls();
});
workspacePublish.addEventListener('click', async () => {
  if (!workspaceFile || workspacePublish.disabled) return;
  workspaceBusy = true;
  syncControls();
  try {
    const result = await workspacePost('publish', {
      ...workspaceFile,
      content: workspaceAfter.value,
    });
    proposalReady = false;
    if (result.source === 'github') {
      workspaceCompare.href = result.compareUrl;
      workspaceCompare.hidden = false;
      feedback(`رُفع التعديل إلى الفرع ${result.branch}.`);
    } else {
      resetWorkspace();
      feedback('حُفظ الملف على الجهاز.');
    }
  } catch (error) {
    feedback(error.message, true);
  } finally {
    workspaceBusy = false;
    syncControls();
  }
});

function clearConversation() {
  turns = [];
  transcript.replaceChildren();
}

async function loadModels() {
  catalogRequest?.abort();
  const loading = new AbortController();
  catalogRequest = loading;
  const provider = activeProvider();
  clearConversation();
  modelField.replaceChildren();
  clearConsents();
  syncControls();
  if (!provider) return;
  feedback('جارٍ جلب النماذج…');
  try {
    const response = await api(`/api/ai/providers/${provider.id}/models`, {
      signal: loading.signal,
    });
    const models = await response.json();
    if (loading.signal.aborted) return;
    modelField.replaceChildren(
      ...models.map((model) => new Option(model.label, model.id)),
    );
    feedback(
      models.length
        ? `المزوّد جاهز · ${tiers[provider.tier]}`
        : 'لم يعرض المزوّد أي نماذج متاحة.',
    );
  } catch (error) {
    if (!loading.signal.aborted) feedback(error.message, true);
  } finally {
    if (catalogRequest === loading) {
      catalogRequest = undefined;
      syncControls();
    }
  }
}

unlock.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = unlock.querySelector('button');
  button.disabled = true;
  accessToken = tokenField.value.trim();
  try {
    const response = await api('/api/ai/providers');
    providers = await response.json();
    tokenField.value = '';
    providerField.replaceChildren(
      ...providers.map(
        (provider) =>
          new Option(
            `${provider.label} · ${tiers[provider.tier]}`,
            provider.id,
          ),
      ),
    );
    unlock.hidden = true;
    room.hidden = false;
    syncReviewers();
    await Promise.all([
      loadModels(),
      loadWorkspace().catch((error) => feedback(error.message, true)),
    ]);
  } catch (error) {
    accessToken = '';
    feedback(error.message, true);
  } finally {
    button.disabled = false;
  }
});

providerField.addEventListener('change', () => {
  syncReviewers();
  loadModels();
});
reviewerField.addEventListener('change', loadReviewerModels);
reviewModel.addEventListener('change', clearConsents);
modelField.addEventListener('change', () => {
  clearConversation();
  clearConsents();
  syncControls();
});
reset.addEventListener('click', clearConversation);
lock.addEventListener('click', () => {
  if (workspaceBusy) return;
  catalogRequest?.abort();
  reviewerRequest?.abort();
  accessToken = '';
  resetWorkspace();
  clearConversation();
  messageField.value = '';
  providers = [];
  scanId = '';
  fileField.replaceChildren(new Option('دون ملف', ''));
  room.hidden = true;
  unlock.hidden = false;
  feedback('');
  tokenField.focus();
});

loadFiles.addEventListener('click', async () => {
  loadFiles.disabled = true;
  const currentToken = accessToken;
  try {
    const response = await api('/api/project/tree');
    const files = await response.json();
    if (currentToken !== accessToken || room.hidden) return;
    scanId = response.headers.get('X-Scan-Id') || '';
    fileField.replaceChildren(
      new Option('دون ملف', ''),
      ...files.map((file) => new Option(file.path, file.path)),
    );
    share.checked = false;
    syncControls();
    feedback('حمّلت قائمة ملفات آخر فحص.');
  } catch (error) {
    if (currentToken === accessToken) feedback(error.message, true);
  } finally {
    loadFiles.disabled = false;
  }
});

fileField.addEventListener('change', () => {
  if (fileField.value) resetWorkspace();
  share.checked = false;
  syncControls();
});

function addTurn(role, text) {
  const item = document.createElement('li');
  item.dataset.role = role;
  const label = document.createElement('strong');
  label.textContent = role === 'user' ? 'أنت' : 'المساعد';
  const content = document.createElement('p');
  content.dir = 'auto';
  content.textContent = text;
  item.append(label, content);
  transcript.append(item);
  if (transcript.children.length > 80) transcript.firstElementChild.remove();
  transcript.scrollTop = transcript.scrollHeight;
  return content;
}

async function readReply(response, output) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;
  try {
    while (!finished) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      let end;
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const [event, line] = frame.split('\n', 2);
        const data = JSON.parse(line.slice('data: '.length));
        if (event === 'event: delta') {
          const follow =
            transcript.scrollHeight -
              transcript.scrollTop -
              transcript.clientHeight <
            40;
          output.append(data.text);
          if (follow) transcript.scrollTop = transcript.scrollHeight;
        } else if (event === 'event: stage') {
          const label =
            providers.find(({ id }) => id === data.provider)?.label ??
            data.provider;
          const stage = {
            draft: 'إعداد المسودة',
            review: 'مراجعة المسودة',
            final: 'صياغة الرد النهائي',
          }[data.stage];
          if (stage) feedback(`${stage} · ${label}`);
        } else if (event === 'event: error') {
          throw new Error(errorText(data.code, data.retryAfter));
        } else if (event === 'event: done') {
          finished = true;
          break;
        }
      }
    }
    if (!finished) throw new Error(messages.AI_INCOMPLETE);
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const content = messageField.value.trim();
  if (!content || send.disabled) return;
  const edit = workspaceFile;
  if (edit) {
    proposalReady = false;
    workspaceCompare.hidden = true;
  }
  request = new AbortController();
  syncControls();
  stop.hidden = false;
  for (const field of [
    providerField,
    modelField,
    reviewerField,
    reviewModel,
    reset,
    lock,
    fileField,
    loadFiles,
  ])
    field.disabled = true;

  const next = { role: 'user', content };
  const history = edit ? [next] : [...turns.slice(-12), next];
  while (
    history.reduce((size, turn) => size + turn.content.length, 0) > 24_000 &&
    history.length > 1
  ) {
    history.splice(0, 2);
  }
  const body = {
    provider: providerField.value,
    model: modelField.value,
    messages: history,
    ...(reviewerField.value && {
      reviewer: { provider: reviewerField.value, model: reviewModel.value },
      shareReview: reviewShare.checked,
    }),
    ...(edit && { edit: { ...edit, shareCloud: share.checked } }),
    ...(!edit &&
      fileField.value && {
        attachment: {
          path: fileField.value,
          scanId,
          shareCloud: share.checked,
        },
      }),
    ...(paid.checked && { allowPaid: true }),
  };
  clearConsents();
  messageField.value = '';
  const sent = addTurn('user', content);
  const output = addTurn('assistant', '');
  feedback('جارٍ إعداد الرد…');

  try {
    const response = await api('/api/ai/chat', {
      method: 'POST',
      signal: request.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    await readReply(response, output);
    if (edit) {
      const answer = output.textContent;
      const fence = String.fromCharCode(96).repeat(3);
      const lines = answer.trimEnd().split('\n');
      workspaceAfter.value =
        lines[0].startsWith(fence) && lines.at(-1) === fence
          ? lines.slice(1, -1).join('\n') + '\n'
          : answer;
      proposalReady =
        workspaceAfter.value.length > 0 &&
        workspaceAfter.value.length <= 8000 &&
        workspaceAfter.value !== get('workspace-before').textContent;
      get('chat-workspace').open = true;
      feedback(
        proposalReady
          ? 'اكتمل الاقتراح. راجع المحتوى المعروض قبل الحفظ.'
          : 'راجع حجم الاقتراح والمحتوى الأصلي قبل الحفظ.',
      );
    } else {
      turns = [...history, { role: 'assistant', content: output.textContent }];
      feedback('اكتمل الرد.');
    }
  } catch (error) {
    if (!output.textContent) {
      sent.parentElement.remove();
      output.parentElement.remove();
      if (!messageField.value) messageField.value = content;
    }
    feedback(
      request.signal.aborted ? 'أوقفت الرد.' : error.message,
      !request.signal.aborted,
    );
  } finally {
    request = undefined;
    stop.hidden = true;
    for (const field of [
      providerField,
      modelField,
      reviewerField,
      reviewModel,
      reset,
      lock,
      fileField,
      loadFiles,
    ])
      field.disabled = false;
    syncControls();
  }
});

stop.addEventListener('click', () => request?.abort());
