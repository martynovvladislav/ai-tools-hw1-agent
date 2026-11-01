# Coding Agent

Консольный агент на Java 21: получает задачу, читает и изменяет файлы, запускает
проверки через Bash. История, результаты инструментов, задачи, бюджет и журнал
изменений сохраняются в SQLite.

## Сборка и тесты

Нужны JDK 21, GNU Bash и Python 3.10+. На Windows можно использовать Git Bash.
Maven Wrapper загружает Maven; отдельная установка не требуется. Для первой
сборки нужен доступ к Maven Central. Зависимости включаются в исполняемый JAR.

Из корня репозитория в PowerShell:

```powershell
.\mvnw.cmd verify
java -jar target/coding-agent.jar --help
java -jar target/coding-agent.jar doctor --bash 'C:\Program Files\Git\bin\bash.exe' --python python
```

В Linux/WSL: `sh ./mvnw verify`, Bash — `/bin/bash`, Python — `python3`.
В IntelliJ IDEA откройте `pom.xml` как Maven-проект с JDK 21.
`doctor` проверяет среду без изменения проекта и открытия базы.
Настройка тестового окружения и покрытые случаи описаны в [docs/testing.md](docs/testing.md).

## Подготовка рабочего проекта

В `workspace` лежит исходный проект с ошибкой в расчёте заказа и двумя тестами.
Работайте с копией, чтобы можно было повторить пример с исходным состоянием:

```powershell
$demo = Join-Path 'target' ('demo-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$demo/workspace" | Out-Null
Copy-Item -LiteralPath 'workspace/order.py','workspace/test_order.py' -Destination "$demo/workspace"
$common = @('--workspace', "$demo/workspace", '--state-dir', "$demo/state",
            '--bash', 'C:\Program Files\Git\bin\bash.exe', '--python', 'python')
$saved = @('--workspace', "$demo/workspace", '--state-dir', "$demo/state")
```

Замените пути Bash и Python на свои. Каталог состояния должен находиться вне
рабочего проекта. Каждый `run` и `plan` создаёт новый запуск и печатает `run_id`.
Массивы `$common` и `$saved` нужно задать в текущем сеансе PowerShell перед
использованием примеров. Без `--bash` выбирается установленный Git Bash
из `C:\Program Files\Git\bin\bash.exe`, иначе — `bash` из PATH.
Для независимого примера повторите блок подготовки, создав свежую копию.
В следующих командах подставляйте полученный идентификатор:

```powershell
$runId = 'идентификатор-из-вывода-команды'
```

## Запуск с моделью

Используется OpenAI-совместимый Chat Completions API с function calling.
Для Plan Mode сервер также должен поддерживать `response_format` с JSON Schema.
Переменные задаются в оболочке; файл `.env` автоматически не загружается.
Шаблон настроек — [.env.example](.env.example).

| Переменная | Значение |
|---|---|
| `OPENAI_BASE_URL` | Базовый адрес API, включая `/v1`, если этого требует сервер |
| `OPENAI_MODEL` | Имя модели с поддержкой инструментов |
| `OPENAI_API_KEY` | Ключ; необязателен для серверов без авторизации |
| `OPENAI_TIMEOUT_SECONDS` | Таймаут запроса в секундах, по умолчанию 60 |

Для локальной Ollama создайте профиль с окном 16384 токена. В отдельном терминале
должен работать `ollama serve`:

```powershell
ollama pull qwen3:4b
ollama create qwen3:4b-agent -f examples/ollama.Modelfile

$env:OPENAI_BASE_URL = 'http://localhost:11434/v1/'
$env:OPENAI_MODEL = 'qwen3:4b-agent'
$env:OPENAI_API_KEY = 'ollama'
$env:OPENAI_TIMEOUT_SECONDS = '300'

java -jar target/coding-agent.jar run --task 'Исправь расчёт итоговой стоимости заказа, чтобы тесты проходили. Тесты не меняй. Сам найди нужные файлы, внеси исправление и запусти тесты.' @common
```

Для другого сервера замените переменные окружения. Результат решения зависит от
ответов выбранной модели; runtime проверяет выполнение инструментов и критерии задач.

Пример запуска в Linux/WSL после настройки тех же API-переменных:

```bash
demo=$(mktemp -d)
mkdir "$demo/workspace"
cp workspace/order.py workspace/test_order.py "$demo/workspace/"
java -jar target/coding-agent.jar run --task 'Fix the order calculation; preserve tests' \
  --workspace "$demo/workspace" --state-dir "$demo/state" --bash /bin/bash --python python3
```

Коды завершения: 0 — успех, 1 — ошибка исполнения, 2 — неверные аргументы или
конфигурация. При остановке сохранённый запуск можно продолжить через `resume`.

## Воспроизводимый пример без API

Для свежей копии проекта из блока подготовки:

```powershell
java -jar target/coding-agent.jar run --task 'Исправь расчёт заказа, не меняя тесты' @common --script examples/order-repair-responses.json
```

`--script` читает заранее подготовленные ответы модели из JSON. Инструменты,
Bash, тесты контрольного проекта и SQLite работают по-настоящему. Этот режим
показывает работу runtime; выбор действий и исправление уже записаны в сценарии.
В автоматических тестах настоящая модель не вызывается.

## Планирование и задачи

На свежей копии проекта запустите Plan Mode:

```powershell
java -jar target/coding-agent.jar plan --task 'Исправь расчёт заказа, не меняя тесты' @common --script examples/order-plan-responses.json
```

Во время планирования доступны только инструменты чтения. Запись и весь Bash
запрещены runtime до утверждения. План содержит цель, шаги, инструменты,
зависимости, проверки результата, риски, допущения и критерий готовности.
При работе с API модель изучает проект, затем отдельный запрос формирует план
по JSON Schema. Готовый корректный JSON из `--script` принимается сразу.
Runtime дополнительно проверяет зависимости и критерии; неверный план возвращается
модели для исправления. Все запросы используют общий бюджет запуска.
После его сохранения запуск ждёт в состоянии `AWAITING_APPROVAL`.

Подставьте новый `$runId`, затем просмотрите план и утвердите его:

```powershell
$runId = 'run_id-из-вывода-plan'
java -jar target/coding-agent.jar show --run-id $runId @saved
java -jar target/coding-agent.jar approve --run-id $runId @saved
java -jar target/coding-agent.jar resume --run-id $runId @common --script examples/order-plan-responses.json
java -jar target/coding-agent.jar tasks --run-id $runId @saved
```

`approve` сохраняет решение; исполнение начинается командой `resume`. Вместо
утверждения можно выполнить `reject --run-id $runId @saved`: запуск завершится
с ошибкой. Для работы с настоящей моделью уберите `--script` из `plan` и `resume`.
Перед утверждением проверьте пути, полноту плана и способ проверки результата.
`approve` принимает `--run-id`, повторно передавать `--task` не нужно.

Сценарий [order-plan-responses.json](examples/order-plan-responses.json) содержит
три задачи для контрольного проекта:

| Задача | Зависит от | Подтверждение результата |
|---|---|---|
| `inspect` | — | Полное успешное чтение исходника и тестов |
| `fix` | `inspect` | Запись `order.py` и чтение исправленного кода |
| `verify` | `fix` | Успешный Bash с выводом `Ran 2 tests` |

В плане от модели набор задач и проверки зависят от запроса и изученного проекта.

Runtime последовательно выбирает задачи с завершёнными зависимостями. Их состояния
выводятся чеклистом, а `tasks` показывает сохранённые подтверждения. Текстового
заявления модели недостаточно: нужны подходящие результаты инструментов этой задачи.
У обычного `run` одна задача `main`, для завершения которой требуется успешный Bash
после последнего изменения. Завершённые задачи после `resume` не запускаются заново.

## Восстановление

Продолжение использует прежние каталоги проекта и состояния, а также прежние
настройки модели или тот же файл `--script`:

```powershell
java -jar target/coding-agent.jar resume --run-id $runId @common
```

Сначала остановите исходный процесс: одновременное исполнение одного запуска
несколькими процессами не поддерживается. Завершённые вызовы не повторяются.
Если действие началось, но результат не сохранён, оно становится `UNCERTAIN`
и требует явного решения пользователя:

| `--action` | Поведение |
|---|---|
| `retry` | Новая попытка; разрешена только для `list_files` и `read_file` |
| `skip` | Сохранить ошибку инструмента и продолжить; файлы остаются как есть |
| `fail` | Завершить запуск с ошибкой |

Для неопределённого чтения достаточно указать ID вызова и действие:

```powershell
$callId = 'идентификатор-вызова'
java -jar target/coding-agent.jar resume --run-id $runId @common --call-id $callId --action retry
```

Для неопределённой записи или Bash сначала выполните `resume` без действия.
Он покажет изменения и `observation` — хеш просмотренного дерева. При решении
добавьте `--changes include` для отнесения изменений к запуску или `--changes exclude`
для сохранения их как внешних. Если дерево изменится после просмотра, нужен новый просмотр.

Сбой между действием и записью результата можно воспроизвести на свежей копии:

```powershell
java -jar target/coding-agent.jar run --task 'Исправь расчёт заказа, не меняя тесты' @common --script examples/order-repair-responses.json --crash-after-call fix-code
$runId = 'run_id-из-вывода-прерванного-run'
java -jar target/coding-agent.jar resume --run-id $runId @common
$observation = 'хеш-из-вывода-resume'
java -jar target/coding-agent.jar resume --run-id $runId @common --call-id fix-code --action skip --changes include --observation $observation --script examples/order-repair-responses.json
```

`write_file` и Bash нельзя повторять через `retry`: неизвестно, что они уже успели
сделать. Для примера безопасного повтора используйте сбой на `read-code` и `retry`.
При продолжении не передавайте `--crash-after-call` повторно.

## Изменения и откат

```powershell
java -jar target/coding-agent.jar diff --run-id $runId @saved
java -jar target/coding-agent.jar rollback --run-id $runId @saved
```

Журнал учитывает создание, изменение и удаление файлов, в том числе через Bash.
Перед каждым действием с побочным эффектом дерево сравнивается с последним снимком.
При внешней правке действие останавливается до исполнения.

Откат сначала проверяет все затронутые пути и их хеши. При конфликте файлы не
перезаписываются. Остальные пути сохраняются. После успеха запуск получает статус
`ROLLED_BACK`; историю и бюджет можно просмотреть. Прерванный откат продолжается
повторной командой `rollback`. Неопределённые вызовы нужно разрешить до отката.

## Инструменты и ограничения

| Инструмент | Строковые аргументы | Результат |
|---|---|---|
| `list_files` | `path` | Рекурсивный список файлов |
| `read_file` | `path` | Содержимое файла в UTF-8 |
| `write_file` | `path`, `content` | Создание или замена файла; родитель должен существовать |
| `Bash` | `command`, `cwd` | stdout, stderr, код завершения и признак таймаута |

Имена и аргументы проверяются по JSON Schema. Файловые пути и начальный `cwd`
ограничены workspace. Bash выполняет команды с правами процесса; изоляции ОС нет.
Для запуска Python в Bash доступна переменная `AGENT_PYTHON`, например
`"$AGENT_PYTHON" -B -m unittest -v`.

Bash ограничен 10 секундами и 16 КиБ stdout/stderr суммарно. Результаты чтения
и списка файлов также ограничены; обрезка отмечается `truncated=true`.
Снимки включают все файлы и каталоги, без исключений для скрытых или генерируемых
файлов. Предел снимка — 2 МиБ содержимого и 1000 путей. Символьные ссылки,
перенаправляющие каталоги и специальные файлы не поддерживаются.
Одновременные внешние изменения во время исполнения или отката не поддерживаются.

Лимиты бюджета задаются при `run` или `plan`:

| Параметр | По умолчанию |
|---|---:|
| `--max-model-requests` | 40 |
| `--max-tool-calls` | 100 |
| `--max-side-effects` | 30 |
| `--max-result-bytes` | 262144 |

Бюджет общий для планирования, исполнения и восстановления. `approve` и `resume`
не сбрасывают счётчики; через `resume` лимиты изменить нельзя. Если ресурсов не
хватает на всю пачку вызовов модели, не выполняется ни один её вызов.
`--max-turns` отдельно ограничивает ответы модели за одно обращение к CLI
(по умолчанию 20), не заменяя сохраняемый бюджет. Расход выводится командой `show`.

## Устройство проекта

Логику удобно читать с [Agent.java](src/main/java/dev/codingagent/agent/Agent.java).
Карта пакетов, порядок операций, хранение и правила восстановления описаны
в [docs/architecture.md](docs/architecture.md). Автоматические проверки —
в [docs/testing.md](docs/testing.md).
