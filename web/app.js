const form = document.getElementById('scan-form');
const branch = document.getElementById('branch');
const scanButton = document.getElementById('scanBtn');
const status = document.getElementById('status');
const results = document.getElementById('results');
const resultRef = document.getElementById('result-ref');
const findings = document.getElementById('findings');
const filter = document.getElementById('filter');
const filesStatus = document.getElementById('files-status');
const filesList = document.getElementById('files');
const preview = document.getElementById('preview');
const previewTitle = document.getElementById('preview-title');
const previewStatus = document.getElementById('preview-status');
const code = document.getElementById('code');
const number = new Intl.NumberFormat('ar');

const errorLabels = {
  GITHUB_SETUP_REQUIRED: 'اضبط اسم المالك والمستودع على الخادم قبل الفحص.',
  INVALID_REQUEST: 'تحقّق من قيمة الفرع أو مسار الملف.',
  SCAN_IN_PROGRESS: 'هناك فحص جارٍ. حاول مجددًا بعد انتهائه.',
  SCAN_CHANGED: 'انتهت صلاحية النتيجة أو استُبدلت. شغّل فحصًا جديدًا.',
  FILE_NOT_FOUND: 'الملف غير متاح في نتيجة الفحص.',
  GITHUB_NOT_FOUND: 'تعذّر الوصول إلى المستودع أو المرجع على GitHub.',
  GITHUB_UNAVAILABLE: 'تعذّر إكمال الاتصال بـ GitHub. حاول مرة أخرى.',
  GITHUB_TIMEOUT: 'انتهت مهلة الاتصال بـ GitHub. حاول مرة أخرى.',
  PROJECT_TOO_LARGE: 'يتجاوز المستودع حدود حجم الفحص.',
};

let scannedFiles = [];
let scanId = '';
let selectedPath = '';
let fileRequest;

function setStatus(message, state = '') {
  status.dataset.state = state;
  status.textContent = message;
}

async function responseError(response) {
  const data = await response.json().catch(() => ({}));
  if (data.code === 'GITHUB_RATE_LIMIT') {
    const delay = response.headers.get('Retry-After');
    return delay && /^\d+$/.test(delay)
      ? `بلغ GitHub حدّ الطلبات. حاول بعد ${number.format(delay)} ثانية.`
      : 'بلغ GitHub حدّ الطلبات. حاول لاحقًا.';
  }
  return Object.hasOwn(errorLabels, data.code)
    ? errorLabels[data.code]
    : 'تعذّر إكمال الطلب. حاول مرة أخرى.';
}

async function fetchJson(url, options) {
  let response;
  try {
    response = await fetch(url, options);
  } catch {
    throw new Error('تعذّر الاتصال بالخادم. تحقّق من الاتصال وحاول مجددًا.');
  }
  if (!response.ok) throw new Error(await responseError(response));
  return { response, data: await response.json() };
}

function renderFindings(graph) {
  const groups = [
    ['الدورات', graph.cycles.map((cycle) => cycle.join(' → '))],
    ['الملفات المعزولة', graph.orphans],
    [
      'الرموز المكررة',
      Object.entries(graph.duplicateSymbols).map(
        ([symbol, paths]) => `${symbol} · ${paths.join(', ')}`,
      ),
    ],
    [
      'الاستيرادات المحلية غير المطابقة',
      Object.entries(graph.missing).flatMap(([path, imports]) =>
        imports.map((specifier) => `${path} → ${specifier}`),
      ),
    ],
  ];
  findings.replaceChildren();
  for (const [label, values] of groups) {
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    summary.textContent = `${label} (${number.format(values.length)})`;
    details.append(summary);
    if (values.length) {
      const list = document.createElement('ul');
      list.className = 'details-list';
      for (const value of values) {
        const item = document.createElement('li');
        const text = document.createElement('bdi');
        text.dir = 'ltr';
        text.className = 'code-text';
        text.textContent = value;
        item.append(text);
        list.append(item);
      }
      details.append(list);
    } else {
      const message = document.createElement('p');
      message.className = 'empty';
      message.textContent = 'لا توجد نتائج.';
      details.append(message);
    }
    findings.append(details);
  }
}

function renderFiles() {
  const term = filter.value.trim().toLowerCase();
  const matches = scannedFiles.filter((path) =>
    path.toLowerCase().includes(term),
  );
  filesStatus.textContent = matches.length
    ? `${number.format(matches.length)} ملف`
    : term
      ? 'لا توجد ملفات تطابق البحث.'
      : 'لا توجد ملفات نصية قابلة للعرض.';

  const items = document.createDocumentFragment();
  for (const path of matches) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.dir = 'ltr';
    button.className = 'code-text';
    button.textContent = path;
    button.setAttribute('aria-pressed', String(path === selectedPath));
    button.addEventListener('click', () => previewFile(path, button));
    item.append(button);
    items.append(item);
  }
  filesList.replaceChildren(items);
}

async function previewFile(path, button) {
  fileRequest?.abort();
  const request = new AbortController();
  fileRequest = request;
  filesList
    .querySelector('[aria-pressed="true"]')
    ?.setAttribute('aria-pressed', 'false');
  button.setAttribute('aria-pressed', 'true');
  selectedPath = path;
  preview.hidden = false;
  previewTitle.textContent = path;
  previewStatus.textContent = 'جارٍ تحميل الملف…';
  code.hidden = true;
  code.textContent = '';

  try {
    const query = new URLSearchParams({ path });
    if (scanId) query.set('scanId', scanId);
    const { data: file } = await fetchJson(`/api/project/file?${query}`, {
      signal: request.signal,
    });
    if (request.signal.aborted) return;
    previewStatus.textContent = `${number.format(file.size)} بايت`;
    code.textContent = file.content;
    code.hidden = false;
  } catch (error) {
    if (!request.signal.aborted) previewStatus.textContent = error.message;
  }
}

async function scanProject(event) {
  event.preventDefault();
  if (scanButton.disabled) return;
  const ref = branch.value.trim();
  scanButton.disabled = true;
  branch.disabled = true;
  setStatus('جارٍ فحص المشروع…', 'loading');

  try {
    const { response, data } = await fetchJson('/api/project/scan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(ref ? { branch: ref } : {}),
    });
    fileRequest?.abort();
    selectedPath = '';
    preview.hidden = true;
    code.textContent = '';
    scanId = response.headers.get('X-Scan-Id') || '';
    scannedFiles = data.files;
    filter.value = '';
    resultRef.textContent = ref ? `المرجع: ${ref}` : 'الفرع الافتراضي';
    for (const [id, value] of [
      ['file-count', data.fileCount],
      ['edge-count', data.edgeCount],
      ['cycle-count', data.cycleCount],
      ['orphan-count', data.orphanCount],
      ['duplicate-count', data.duplicateCount],
    ]) {
      document.getElementById(id).textContent = number.format(value);
    }
    renderFindings(data.graph);
    renderFiles();
    results.hidden = false;
    setStatus('اكتمل الفحص.', 'success');
  } catch (error) {
    setStatus(error.message, 'error');
  } finally {
    scanButton.disabled = false;
    branch.disabled = false;
  }
}

form.addEventListener('submit', scanProject);
filter.addEventListener('input', renderFiles);
