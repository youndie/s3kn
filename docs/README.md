# Документация s3kn

Слои, связанные ссылками сверху вниз:

```
[ Research — почему архитектура именно такая, что проверено, что гипотеза ]
                              │
[ Feature — что делает библиотека и зачем + BDD-сценарии = критерии приёмки ]
                              │
[ Protocol — контракт на проводе: URL, подпись, коды ошибок ]
                              │
[ Module — зона ответственности модуля, зависимости, сборка, грабли ]
```

| Слой | Папка | Отвечает на вопрос | Источник правды |
|---|---|---|---|
| Research | `research/` | Почему выбрано так, что проверено, чем платим | артефакты зависимостей и `spec/` |
| Feature | `features/` | Что библиотека умеет и как это принимается | этот репозиторий + тесты |
| Protocol | `api/` | Что уходит и приходит по HTTP | `spec/` |
| Module | `services/` | Что за модуль, от чего зависит, как собирается | код модуля |

Слой экранов не заводится: клиента у библиотеки нет.

## Документы

- [research/research-architecture.md](research/research-architecture.md) — **точка входа**.
  Проверенные факты о Ktor, движке curl и SigV4, принятые решения, риски, открытые вопросы.
- [api/protocol-s3.md](api/protocol-s3.md) — контракт на проводе: адресация, кодирование ключа,
  подпись, семь операций, ошибки. По нему пишутся тесты.
- [spec/README.md](spec/README.md) — что лежит в `spec/`, откуда взято, под какой лицензией
  и как на это ссылаться из тестов.
- [services/s3-core.md](services/s3-core.md) — модель, кодирование, конфигурация; без зависимостей.
- [services/s3-sigv4.md](services/s3-sigv4.md) — подпись и presign; без сети и без движка.
- [services/s3-client.md](services/s3-client.md) — семь операций поверх `HttpClient`.
- [services/s3-testing.md](services/s3-testing.md) — векторы и переключатели тестов; не публикуется.
- [features/feature-object-io.md](features/feature-object-io.md) — put / get / head / delete.
- [features/feature-listing.md](features/feature-listing.md) — перечисление бакета.
- [features/feature-multipart.md](features/feature-multipart.md) — многочастная загрузка.
- [features/feature-presign.md](features/feature-presign.md) — подписанные ссылки.
- [../BACKLOG.md](../BACKLOG.md) — вехи M0…M8 и задачи `M-NN`.
- [../RELEASING.md](../RELEASING.md) — что публикуется, куда и почему одним заданием.

## Карта покрытия

### Research (1/1)
- [x] [research-architecture](research/research-architecture.md)

### Protocol (1/1)
- [x] [protocol-s3](api/protocol-s3.md) — закрыт целиком, все семь операций проверены живыми запросами

### Features (4/4)
- [x] [feature-object-io](features/feature-object-io.md) — put / get / head / delete
- [x] [feature-listing](features/feature-listing.md) — перечисление бакета
- [x] [feature-multipart](features/feature-multipart.md) — многочастная загрузка
- [x] [feature-presign](features/feature-presign.md) — подписанные ссылки

### Modules (4/4)
- [x] [s3-core](services/s3-core.md) — модель, кодирование, конфигурация
- [x] [s3-sigv4](services/s3-sigv4.md) — подпись и presign
- [x] [s3-client](services/s3-client.md) — семь операций поверх `HttpClient`
- [x] [s3-testing](services/s3-testing.md) — векторы и переключатели; **не публикуется**

## Соглашения

- **`id` во frontmatter равен имени файла.** Ссылки между слоями — по id во frontmatter и обычными
  markdown-ссылками в тексте.
- **`main` описывает то, что есть.** Замысел живёт в открытом PR (`status: draft`) или помечен
  словом «целевое»/«гипотеза» прямо в тексте.
- **Проверенное отделяется от предполагаемого** явно, колонкой «где проверено».
- **Ссылка на спецификацию — это ссылка на строку в `spec/`**: `s3-service-2.json:1596`,
  `reference/botocore-auth.py:538`, `aws-sig-v4-test-suite/get-utf8/`. Не «см. документацию AWS».
  Исключение — формы данных модели: на них ссылаемся путём внутри JSON
  (`shapes.UploadPartOutput.members.ETag`), потому что номера строк в `shapes` едут при каждом
  обновлении модели.
- **Якоря кода вместо пересказа кода.** Абзац, который можно заменить путём к файлу, заменяется
  путём к файлу.
- **Секция Quirks не удаляется** при правке документа — только после проверки, что поведение
  действительно изменилось.
- **Язык документации — русский, язык кода — английский.** Комментарии, KDoc, имена тестов,
  сообщения исключений и сообщения коммитов пишутся по-английски: библиотека открытая.
  Корневой `README.md` — тоже английский, это витрина проекта.
- Имена заголовков, query-параметров и кодов ошибок — вербатим, как на проводе
  (`x-amz-content-sha256`, `list-type=2`, `NoSuchUpload`), в любом тексте.

## Шаблоны

`templates/feature.md`, `templates/service.md`, `templates/endpoint.md`,
`templates/research-architecture.md` — копируются и заполняются. Секции с пометкой
`<!-- optional -->` можно удалять.
