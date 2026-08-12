'use strict';

const vscode = acquireVsCodeApi();
let state = {};
let selectedLine = -1;
let contextWord = '';
let renderedCurrentLine = -1;
let renderedRoutineName = '';
let requestedRoutineName = '';
let routineOptionsOpen = false;
let routineShowAll = false;
let logVisible = false;

const $ = id => document.getElementById(id);
const post = (type, extra = {}) => vscode.postMessage({ type, ...extra });
const keywords = new Set(('ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BOTH BY CALL CASCADE CASE CHANGE CHECK COLLATE COLUMN CONDITION CONNECTION CONSTRAINT CONTINUE CONVERT CREATE CROSS CURRENT_USER CURSOR DATABASE DATABASES DECLARE DEFAULT DELAYED DELETE DELIMITER DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DO DROP DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXISTS EXIT EXPLAIN FALSE FETCH FOR FORCE FOREIGN FROM FULLTEXT GENERATED GET GRANT GROUP HANDLER HAVING HIGH_PRIORITY IF IGNORE IN INDEX INFILE INNER INOUT INSENSITIVE INSERT INTERVAL INTO INVOKER IS ITERATE JOIN KEY KEYS KILL LATERAL LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD LOCK LOOP LOW_PRIORITY MASTER_BIND MATCH MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG NULL OF ON OPTIMIZE OPTION OPTIONALLY OR ORDER OUT OUTER OUTFILE OVER PARTITION PRECISION PRIMARY PROCEDURE PURGE RANGE READ READS READ_WRITE RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESIGNAL RESTRICT RETURN RETURNS REVOKE RIGHT RLIKE ROW ROWS SCHEMA SCHEMAS SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SPATIAL SPECIFIC SQL SQLSTATE SSL STARTING STORED STRAIGHT_JOIN TABLE TERMINATED THEN TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING VALUES VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE XOR ZEROFILL BEGIN END FUNCTION').split(' '));
const types = new Set(('BIGINT BINARY BIT BLOB BOOL BOOLEAN CHAR CHARACTER DATE DATETIME DEC DECIMAL DOUBLE ENUM FIXED FLOAT INT INTEGER JSON LONGBLOB LONG LONGTEXT MEDIUMBLOB MEDIUMINT MEDIUMTEXT NATIONAL NCHAR NUMERIC REAL SET SIGNED SMALLINT TEXT TIME TIMESTAMP TINYBLOB TINYINT TINYTEXT VARBINARY VARCHAR YEAR').split(' '));
const constants = new Set(('CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER FALSE LOCALTIME LOCALTIMESTAMP NULL TRUE UTC_DATE UTC_TIME UTC_TIMESTAMP SQLWARNING SQLEXCEPTION NOT_FOUND').split(' '));
const functions = new Set(('ABS ACOS ADDDATE ADDTIME AES_DECRYPT AES_ENCRYPT ASCII ASIN ATAN AVG BENCHMARK BIN BIT_COUNT CEIL CEILING CHAR_LENGTH CHARACTER_LENGTH COALESCE CONCAT CONCAT_WS CONVERT CONV COS COUNT CRC32 CURDATE CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURTIME DATABASE DATE DATE_ADD DATE_FORMAT DATE_SUB DATEDIFF DAY DAYNAME DAYOFMONTH DAYOFWEEK DAYOFYEAR DEGREES ELT EXP FIELD FIND_IN_SET FLOOR FORMAT FOUND_ROWS FROM_BASE64 GREATEST GROUP_CONCAT HEX HOUR IFNULL INET_ATON INET_NTOA INSTR JSON_ARRAY JSON_EXTRACT JSON_OBJECT LAST_INSERT_ID LCASE LEAST LEFT LENGTH LN LOCATE LOG LOG10 LOWER LPAD LTRIM MAKEDATE MAKETIME MAX MD5 MICROSECOND MID MIN MINUTE MOD MONTH MONTHNAME NOW NULLIF OCT OCTET_LENGTH ORD PI POSITION POW POWER QUARTER RADIANS RAND REPEAT REPLACE REVERSE RIGHT ROUND ROW_COUNT RPAD RTRIM SECOND SHA1 SHA2 SIGN SIN SLEEP SOUNDEX SPACE SQRT STRCMP SUBDATE SUBSTR SUBSTRING SUBSTRING_INDEX SUM SYSDATE TAN TIME_FORMAT TIMEDIFF TIMESTAMPADD TIMESTAMPDIFF TO_BASE64 TRIM TRUNCATE UCASE UNHEX UNIX_TIMESTAMP UPPER UUID WEEK WEEKDAY WEEKOFYEAR YEARWEEK').split(' '));
const objectIntroducers = new Set(('CALL DATABASE FUNCTION FROM JOIN PROCEDURE TABLE TRIGGER UPDATE USE').split(' '));

function routineVariables(ddl, values) {
  const variables = new Set(values.keys());
  const beforeBody = String(ddl || '').split(/\bBEGIN\b/i)[0];
  for (const match of beforeBody.matchAll(/\b(?:IN|OUT|INOUT)\s+`?([A-Za-z_$][\w$]*)`?\s+[A-Za-z]/gi)) variables.add(match[1].toLowerCase());
  for (const match of String(ddl || '').matchAll(/\bDECLARE\s+`?([A-Za-z_$][\w$]*)`?\s+(?!CONDITION\b|CURSOR\b|CONTINUE\b|EXIT\b|HANDLER\b)/gi)) variables.add(match[1].toLowerCase());
  for (const match of String(ddl || '').matchAll(/\bSET\s+`?([A-Za-z_$][\w$]*)`?\s*(?::=|=)/gi)) variables.add(match[1].toLowerCase());
  return variables;
}

function textCell(value, title) {
  const td = document.createElement('td');
  td.textContent = value == null ? '' : String(value);
  td.title = title == null ? td.textContent : String(title);
  return td;
}

function valueMap() {
  const values = new Map();
  for (const watch of state.watches || []) if (watch.value !== undefined) values.set(watch.name.toLowerCase(), watch.value);
  for (const entry of state.log || []) {
    if (entry.varName !== '__BREAKPOINT__' &&
        String(entry.routineName).toLowerCase() === String(state.routine?.name).toLowerCase()) {
      values.set(entry.varName.toLowerCase(), entry.varValue);
    }
  }
  return values;
}

function appendToken(code, part, className, values, watchable = false) {
    const span = document.createElement('span');
    span.textContent = part;
    if (className) span.className = className;
    if (watchable) {
      span.classList.add('identifier');
      span.dataset.word = part;
      const value = values.get(part.toLowerCase());
      if (value !== undefined) { span.classList.add('has-value'); span.title = `${part} = ${value == null ? 'NULL' : value}`; }
      else span.title = `Right-click to add ${part} to Watches`;
    }
    code.appendChild(span);
}

function appendSql(code, line, values, variables, lexerState) {
  let index = 0;
  while (index < line.length) {
    if (lexerState.blockComment) {
      const end = line.indexOf('*/', index);
      const stop = end < 0 ? line.length : end + 2;
      appendToken(code, line.slice(index, stop), 'sql-comment', values);
      index = stop; lexerState.blockComment = end < 0;
      continue;
    }
    if (line.startsWith('/*', index)) {
      lexerState.blockComment = true;
      continue;
    }
    if ((line.startsWith('--', index) && (index + 2 === line.length || /\s/.test(line[index + 2]))) || line[index] === '#') {
      appendToken(code, line.slice(index), 'sql-comment', values); break;
    }
    const char = line[index];
    const prefixedString = line.slice(index).match(/^(?:[bBnNxX]|_[A-Za-z0-9]+)(?=['"])/);
    if (prefixedString) {
      appendToken(code, prefixedString[0], 'sql-string-prefix', values);
      index += prefixedString[0].length;
      continue;
    }
    if (char === "'" || char === '"') {
      let end = index + 1;
      while (end < line.length) {
        if (line[end] === '\\') { end += 2; continue; }
        if (line[end] === char) {
          if (line[end + 1] === char) { end += 2; continue; }
          end++; break;
        }
        end++;
      }
      appendToken(code, line.slice(index, end), 'sql-string', values); index = end; continue;
    }
    if (char === '`') {
      let end = index + 1;
      while (end < line.length) {
        if (line[end] === '`' && line[end + 1] === '`') { end += 2; continue; }
        if (line[end] === '`') { end++; break; }
        end++;
      }
      appendToken(code, line.slice(index, end), 'sql-quoted-identifier', values); index = end; continue;
    }
    const number = line.slice(index).match(/^(?:0x[\da-f]+|0b[01]+|\d+(?:\.\d+)?(?:e[+-]?\d+)?)/i);
    if (number) { appendToken(code, number[0], 'sql-number', values); index += number[0].length; continue; }
    const variable = line.slice(index).match(/^@@?[A-Za-z_$][\w$]*/);
    if (variable) { appendToken(code, variable[0], 'sql-variable', values); index += variable[0].length; continue; }
    const word = line.slice(index).match(/^[A-Za-z_$][\w$]*/);
    if (word) {
      const upper = word[0].toUpperCase();
      const after = line.slice(index + word[0].length);
      const lower = word[0].toLowerCase();
      const qualifiedObject = /\.\s*$/.test(line.slice(0, index));
      const label = /^\s*:/.test(after) || lexerState.previousWord === 'ITERATE' || lexerState.previousWord === 'LEAVE';
      const className = keywords.has(upper) ? 'sql-keyword' : types.has(upper) ? 'sql-type' :
        constants.has(upper) ? 'sql-constant' : functions.has(upper) || /^\s*\(/.test(after) ? 'sql-function' :
          label ? 'sql-label' : objectIntroducers.has(lexerState.previousWord) || qualifiedObject ? 'sql-object' :
            variables.has(lower) ? 'sql-variable' : '';
      appendToken(code, word[0], className, values, className === 'sql-variable' || !className);
      lexerState.previousWord = upper; index += word[0].length; continue;
    }
    const operator = line.slice(index).match(/^(?:<=>|:=|<=|>=|<>|!=|&&|\|\||[-+*/%=<>!&|^~]+)/);
    if (operator) { appendToken(code, operator[0], 'sql-operator', values); index += operator[0].length; continue; }
    appendToken(code, char, '', values); index++;
  }
}

function renderSource() {
  const source = $('source');
  const scrollPane = source.parentElement;
  const scrollTop = scrollPane.scrollTop;
  const scrollLeft = scrollPane.scrollLeft;
  const currentLineChanged = state.currentLine > 0 && state.currentLine !== renderedCurrentLine;
  source.replaceChildren();
  const lines = (state.ddl || '').split(/\r?\n/);
  $('empty').classList.toggle('hidden', Boolean(state.ddl));
  source.classList.toggle('hidden', !state.ddl);
  const values = valueMap();
  const variables = routineVariables(state.ddl, values);
  const fragment = document.createDocumentFragment();
  const lexerState = { blockComment: false };
  lines.forEach((text, index) => {
    const number = index + 1;
    const row = document.createElement('div');
    row.className = 'source-line'; row.dataset.line = String(number);
    if (number === state.currentLine) row.classList.add('current');
    if (number === selectedLine) row.classList.add('selected');
    const gutter = document.createElement('button');
    gutter.className = `gutter ${(state.breakpoints || []).includes(`L${number}`) ? 'breakpoint' : ''}`;
    gutter.title = 'Toggle breakpoint (F9)';
    gutter.addEventListener('click', event => { event.stopPropagation(); post('toggleBreakpoint', { line: number, text }); });
    const lineNumber = document.createElement('span'); lineNumber.className = 'line-number'; lineNumber.textContent = String(number);
    const code = document.createElement('span'); code.className = 'code'; appendSql(code, text, values, variables, lexerState);
    row.append(gutter, lineNumber, code);
    row.addEventListener('click', () => { selectedLine = number; renderSource(); });
    fragment.appendChild(row);
  });
  source.appendChild(fragment);
  renderedCurrentLine = state.currentLine;
  requestAnimationFrame(() => {
    if (currentLineChanged) source.querySelector('.current')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    else { scrollPane.scrollTop = scrollTop; scrollPane.scrollLeft = scrollLeft; }
  });
}

function renderWatches() {
  const body = $('watch-list'); body.replaceChildren();
  for (const watch of state.watches || []) {
    const row = document.createElement('tr'); if (watch.changed) row.className = 'changed';
    row.append(textCell(watch.name), textCell(watch.value === undefined ? '—' : watch.value, watch.value));
    const remove = document.createElement('td'); remove.className = 'remove';
    const button = document.createElement('button'); button.textContent = '×'; button.title = `Remove ${watch.name}`;
    button.addEventListener('click', () => post('removeWatch', { name: watch.name })); remove.appendChild(button); row.appendChild(remove); body.appendChild(row);
  }
  $('watch-all').checked = Boolean(state.watchAll);
}

function renderLog() {
  const body = $('log-list');
  const wrap = body.closest('.table-wrap');
  const previousTop = wrap.scrollTop;
  const followNewest = previousTop < 24;
  body.replaceChildren();
  for (const entry of [...(state.log || [])].reverse()) {
    const row = document.createElement('tr');
    if (entry.varName === '__BREAKPOINT__') row.className = 'log-break';
    const time = String(entry.ts || '').split(' ').pop();
    row.append(textCell(time), textCell(entry.routineName), textCell(entry.label), textCell(entry.varName === '__BREAKPOINT__' ? '⏸ breakpoint' : entry.varName), textCell(entry.varValue, entry.varValue));
    body.appendChild(row);
  }
  requestAnimationFrame(() => { wrap.scrollTop = followNewest ? 0 : previousTop; });
}

function chooseRoutine(routine) {
  if (!routine) return;
  $('routine').value = routine.name;
  closeRoutineOptions();
  if (routine.name.toLowerCase() !== requestedRoutineName.toLowerCase()) {
    requestedRoutineName = routine.name;
    post('load', { name: routine.name });
  }
}

function positionRoutineOptions() {
  if (!routineOptionsOpen) return;
  const rect = $('routine').closest('.routine-combobox').getBoundingClientRect();
  const options = $('routine-options');
  options.style.left = `${Math.max(6, Math.min(rect.left, innerWidth - rect.width - 6))}px`;
  options.style.top = `${rect.bottom + 3}px`;
  options.style.width = `${rect.width}px`;
  options.style.maxHeight = `${Math.max(100, innerHeight - rect.bottom - 12)}px`;
}

function renderRoutineOptions() {
  const options = $('routine-options');
  options.replaceChildren();
  if (!routineOptionsOpen) { options.classList.add('hidden'); return; }
  const query = routineShowAll ? '' : $('routine').value.trim().toLowerCase();
  const routines = (state.routines || []).filter(routine =>
    !query || routine.name.toLowerCase().includes(query) || routine.type.toLowerCase().includes(query));
  for (const routine of routines) {
    const option = document.createElement('button');
    option.type = 'button'; option.className = 'routine-option'; option.setAttribute('role', 'option');
    option.dataset.name = routine.name;
    option.setAttribute('aria-selected', String(routine.name === state.routine?.name));
    const name = document.createElement('span'); name.className = 'routine-option-name'; name.textContent = routine.name;
    const type = document.createElement('span'); type.className = 'routine-option-type'; type.textContent = routine.type === 'PROCEDURE' ? 'Procedure' : 'Function';
    option.append(name, type);
    option.addEventListener('mousedown', event => event.preventDefault());
    option.addEventListener('click', () => chooseRoutine(routine));
    option.addEventListener('keydown', event => {
      const all = [...options.querySelectorAll('.routine-option')];
      const index = all.indexOf(option);
      if (event.key === 'ArrowDown') { event.preventDefault(); (all[index + 1] || all[0])?.focus(); }
      if (event.key === 'ArrowUp') { event.preventDefault(); (all[index - 1] || $('routine'))?.focus(); }
      if (event.key === 'Enter') { event.preventDefault(); chooseRoutine(routine); }
      if (event.key === 'Escape') { event.preventDefault(); closeRoutineOptions(); $('routine').focus(); }
    });
    options.appendChild(option);
  }
  if (!routines.length) {
    const empty = document.createElement('div'); empty.className = 'routine-option-empty'; empty.textContent = 'No matching routines'; options.appendChild(empty);
  }
  options.classList.remove('hidden');
  positionRoutineOptions();
}

function openRoutineOptions(showAll = false) {
  if ($('routine').disabled) return;
  routineOptionsOpen = true;
  routineShowAll = showAll;
  $('routine').setAttribute('aria-expanded', 'true');
  renderRoutineOptions();
}

function closeRoutineOptions() {
  routineOptionsOpen = false;
  routineShowAll = false;
  $('routine').setAttribute('aria-expanded', 'false');
  $('routine-options').classList.add('hidden');
}

function renderToolbar() {
  const picker = $('routine');
  const routineName = state.routine?.name || '';
  if (routineName !== renderedRoutineName) {
    picker.value = routineName;
    renderedRoutineName = routineName;
    requestedRoutineName = routineName;
  }
  picker.disabled = !state.connected || state.active;
  $('routine-toggle').disabled = picker.disabled;
  if (picker.disabled) closeRoutineOptions(); else renderRoutineOptions();
  $('debug').disabled = !state.routine || state.active;
  $('stop').disabled = !state.active;
  $('continue').disabled = !state.paused;
  const inCallee = Boolean(state.inCallee);
  $('step-into').classList.toggle('hidden', inCallee);
  $('step-out').classList.toggle('hidden', !inCallee);
  $('step-into').disabled = !state.paused || !state.canStepInto;
  $('step-out').disabled = !state.paused || !inCallee;
  $('step').disabled = !state.paused;
  $('connect').classList.toggle('hidden', state.connected);
  $('connect').disabled = state.active;
  const banner = $('banner');
  banner.classList.toggle('hidden', !state.active);
  banner.textContent = state.active ? `▶ Debug active — call ${state.rootRoutineName || ''}(…) normally in your SQL client` : '';
}

function render() { renderToolbar(); renderSource(); renderWatches(); renderLog(); }

window.addEventListener('message', event => {
  const message = event.data;
  if (message.type === 'state') {
    const sessionChanged = state.activeSessionId !== message.state.activeSessionId;
    state = message.state;
    if (sessionChanged) selectedLine = -1;
    render();
  }
  if (message.type === 'showConnection') {
    const c = message.connection || {};
    $('db-host').value = c.host || 'localhost'; $('db-port').value = c.port || 3306;
    $('db-user').value = c.user || ''; $('db-database').value = c.database || ''; $('db-password').value = '';
    $('connection-error').textContent = ''; $('connection-dialog').showModal();
  }
  if (message.type === 'connected') $('connection-dialog').close();
  if (message.type === 'error' && $('connection-dialog').open) $('connection-error').textContent = message.message;
  if (message.type === 'toggleSelectedBreakpoint' && selectedLine > 0) {
    const text = (state.ddl || '').split(/\r?\n/)[selectedLine - 1] || '';
    post('toggleBreakpoint', { line: selectedLine, text });
  }
});

$('connect').addEventListener('click', () => post('showConnection'));
$('routine').addEventListener('focus', () => openRoutineOptions(true));
$('routine').addEventListener('input', () => openRoutineOptions(false));
$('routine').addEventListener('keydown', event => {
  if (event.key === 'ArrowDown') {
    event.preventDefault(); openRoutineOptions(routineOptionsOpen ? routineShowAll : true);
    $('routine-options').querySelector('.routine-option')?.focus();
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    const exact = (state.routines || []).find(item => item.name.toLowerCase() === event.target.value.trim().toLowerCase());
    const first = $('routine-options').querySelector('.routine-option');
    chooseRoutine(exact || (state.routines || []).find(item => item.name === first?.dataset.name));
  }
  if (event.key === 'Escape') { event.preventDefault(); closeRoutineOptions(); }
});
$('routine-toggle').addEventListener('click', () => routineOptionsOpen ? closeRoutineOptions() : openRoutineOptions(true));
document.addEventListener('mousedown', event => {
  if (!event.target.closest('.routine-combobox') && !event.target.closest('.routine-options')) closeRoutineOptions();
});
window.addEventListener('resize', positionRoutineOptions);
$('routine').closest('.toolbar').addEventListener('scroll', positionRoutineOptions);
$('debug').addEventListener('click', () => post('deploy'));
$('stop').addEventListener('click', () => post('stop'));
$('continue').addEventListener('click', () => post('continue'));
$('step-into').addEventListener('click', () => post('stepInto'));
$('step-out').addEventListener('click', () => post('stepOut'));
$('step').addEventListener('click', () => post('step'));
$('toggle-log').addEventListener('click', () => {
  logVisible = !logVisible;
  $('log-panel').classList.toggle('hidden', !logVisible);
  $('side-pane').classList.toggle('log-hidden', !logVisible);
  $('toggle-log').textContent = logVisible ? 'Hide Log' : 'Show Log';
});
$('clear-log').addEventListener('click', () => post('clearLog'));
$('watch-all').addEventListener('change', event => post('watchAll', { enabled: event.target.checked }));
$('watch-form').addEventListener('submit', event => { event.preventDefault(); const input = $('watch-name'); post('addWatch', { name: input.value }); input.value = ''; });
$('reset').addEventListener('click', () => post('reset'));
$('connection-form').addEventListener('submit', event => {
  if (event.submitter?.value === 'cancel') return;
  event.preventDefault();
  $('connection-error').textContent = '';
  post('connect', { connection: { host: $('db-host').value.trim(), port: Number($('db-port').value), user: $('db-user').value.trim(), password: $('db-password').value, database: $('db-database').value.trim() } });
});
document.addEventListener('contextmenu', event => {
  const identifier = event.target.closest('.identifier');
  if (!identifier) return;
  event.preventDefault(); contextWord = identifier.dataset.word;
  const menu = $('context-menu'); $('context-add-watch').textContent = `Add “${contextWord}” to Watches`;
  menu.style.left = `${Math.min(event.clientX, innerWidth - 210)}px`; menu.style.top = `${Math.min(event.clientY, innerHeight - 45)}px`; menu.classList.remove('hidden');
});
document.addEventListener('click', event => { if (!event.target.closest('.context-menu')) $('context-menu').classList.add('hidden'); });
$('context-add-watch').addEventListener('click', () => { post('addWatch', { name: contextWord }); $('context-menu').classList.add('hidden'); });
window.addEventListener('keydown', event => {
  if (event.key === 'F5' && state.paused) { event.preventDefault(); post('continue'); }
  if (event.key === 'F7' && event.ctrlKey && state.paused) { event.preventDefault(); post('stepOut'); }
  else if (event.key === 'F7' && state.paused) { event.preventDefault(); post('stepInto'); }
  if (event.key === 'F8' && state.paused) { event.preventDefault(); post('step'); }
});

post('ready');
