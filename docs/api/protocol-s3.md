---
id: protocol-s3
title: Контракт S3 на проводе
status: active
date: 2026-08-17
research: research-architecture
---

# Контракт: S3 на проводе

Что именно уходит и приходит для семи операций v1. **По этому документу пишутся тесты** — каждый
пункт либо превращается в тест, либо помечен как «целевое».

Источники — только из [`docs/spec/`](../spec/): модель API (`s3-service-2.json`), эталонная
реализация подписи (`reference/botocore-auth.py`), векторы (`aws-sig-v4-test-suite/`). Ссылка
вида `s3-service-2.json:1596` указывает на строку в копии.

Статус реализации: закрыты разделы 1–3, 4.1–4.5 и 5. Открыт только 4.6 (multipart) — целевой,
код под ним появится в M7. Приёмка: 34 официальных вектора для общего SigV4,
20 сгенерированных из botocore для правил S3 и presign (`docs/spec/s3-signing-vectors/`), плюс
живые запросы к MinIO из `docker-compose.yml`.

---

## 1. Адресация

Два стиля, выбор — явным параметром конфигурации (ресёрч, Открытый вопрос 3):

| Стиль | URL | `host` в подписи |
|---|---|---|
| path-style | `https://s3.us-east-1.amazonaws.com/<bucket>/<key>` | `s3.us-east-1.amazonaws.com` |
| virtual-hosted | `https://<bucket>.s3.us-east-1.amazonaws.com/<key>` | `<bucket>.s3.us-east-1.amazonaws.com` |

`host` входит в подпись, поэтому стиль обязан быть решён **до** построения canonical request,
а не при отправке.

Правила вывода значения `host` (`botocore-auth.py:81`):

- в нижнем регистре;
- порт отбрасывается, если он дефолтный для схемы (443 для https, 80 для http);
- нестандартный порт остаётся: `localhost:9000`.

Автоопределение стиля по домену **не делается**.

## 2. Кодирование ключа объекта

Одна функция на подпись и на URL (ресёрч, Р4). Правило — из `botocore-auth.py:268`:

- не кодируются: `A-Za-z0-9`, `-`, `_`, `.`, `~`;
- `/` в **пути** не кодируется — он разделитель сегментов;
- всё остальное → `%XX`, шестнадцатеричные заглавными, байты UTF-8;
- пробел → `%20`, никогда не `+`;
- нормализация пути **не делается**: `a/./b` уходит как `a/./b` (`botocore-auth.py:538`).

В canonical query `/` кодируется как `%2F` — там он не разделитель.

Векторы — таблица ниже; ведущий `/` добавляет построитель URL, кодировщик его не выдаёт.
Реализовано: `s3-core/src/commonMain/kotlin/io/github/youndie/s3/UriEncoding.kt`, тесты —
`UriEncodingTest`.

| Ключ | Закодированная форма |
|---|---|
| `hello.txt` | `hello.txt` |
| `my dir/file.txt` | `my%20dir/file.txt` |
| `a+b` | `a%2Bb` |
| `a~b` | `a~b` |
| `файл.txt` | `%D1%84%D0%B0%D0%B9%D0%BB.txt` |
| `🙂` | `%F0%9F%99%82` |
| `a//b` | `a//b` |
| `a/./b` | `a/./b` |

Строка с `🙂` — не мелочь: в Kotlin-строке это суррогатная пара, и кодировщик, идущий по `Char`,
выдаст на ней два символа замены и подпишет не то, что отправит.

> **Ограничение: ключи с сегментами `.` и `..`.** Кодировщик их сохраняет, и подпись считается по
> ним верно, но на Kotlin/Native через движок `ktor-client-curl` такой запрос **не доедет**: libcurl
> приводит путь по RFC 3986 уже после подписи, и `a/./b` уходит как `a/b`. Симптом —
> `SignatureDoesNotMatch` без единого намёка на путь. Проверено живым запросом, подробности и
> цифры — ресёрч, факт 1.9. На JVM (движок CIO) путь доезжает нетронутым.
>
> Отдельно: **MinIO отвергает такие ключи сам**, как и ключи с `//`
> (`XMinioInvalidResourceName`, `XMinioInvalidObjectName`), хотя S3 их принимает. Поэтому в E2E
> они исключены явным списком — ресёрч, факт 1.10.

## 3. Подпись SigV4

### 3.1 Canonical request

```
<METHOD>\n
<CANONICAL_URI>\n
<CANONICAL_QUERY>\n
<CANONICAL_HEADERS>\n
\n
<SIGNED_HEADERS>\n
<PAYLOAD_HASH>
```

`botocore-auth.py:370`. Построчно:

| Строка | Правило | Где проверено |
|---|---|---|
| `CANONICAL_URI` | путь как есть, из раздела 2; пустой путь → `/` | `botocore-auth.py:538` |
| `CANONICAL_QUERY` | ключ и значение кодируются с safe-набором `-_.~`, пары сортируются по закодированному ключу, при равных ключах — по значению, склейка `k=v&k=v`; параметр без значения даёт `k=` | `botocore-auth.py:268` |
| `CANONICAL_HEADERS` | имя в нижнем регистре, `:`, значение с обрезанными краевыми пробелами и схлопнутыми внутренними, `\n` после каждого | `botocore-auth.py:301` |
| `SIGNED_HEADERS` | те же имена в нижнем регистре, отсортированные, через `;` | `botocore-auth.py:325` |
| `PAYLOAD_HASH` | значение `x-amz-content-sha256` | `botocore-auth.py:370` |

### 3.2 String to sign

```
AWS4-HMAC-SHA256\n
<yyyyMMdd'T'HHmmss'Z'>\n
<yyyyMMdd>/<region>/s3/aws4_request\n
<hex(sha256(canonical_request))>
```

`botocore-auth.py:405`. Метка времени — UTC, формат `%Y%m%dT%H%M%SZ` (`:63`); дата в scope — её
первые 8 символов, а не отдельно посчитанная дата (иначе на границе суток они разойдутся).

### 3.3 Ключ и подпись

```
kDate    = HMAC("AWS4" + secret, yyyyMMdd)
kRegion  = HMAC(kDate,   region)
kService = HMAC(kRegion, "s3")
kSigning = HMAC(kService,"aws4_request")
signature= hex(HMAC(kSigning, stringToSign))
```

`botocore-auth.py:417`.

### 3.4 Заголовки запроса

| Заголовок | Значение |
|---|---|
| `Authorization` | `AWS4-HMAC-SHA256 Credential=<ak>/<scope>, SignedHeaders=<h;h>, Signature=<hex>` (`botocore-auth.py:445`) |
| `X-Amz-Date` | метка времени из 3.2 |
| `X-Amz-Content-SHA256` | обязателен для S3 всегда (`botocore-auth.py:490`) |
| `X-Amz-Security-Token` | только при временных ключах; **подписывается** (`botocore-auth.py:455`) |

Значения `X-Amz-Content-SHA256`:

| Значение | Когда |
|---|---|
| hex sha256 тела | тело в памяти либо `signPayload = true` |
| `e3b0c442…b855` | тело пустое (`botocore-auth.py:55`) |
| `UNSIGNED-PAYLOAD` | поток поверх https — умолчание для `put`/`uploadPart` (ресёрч, Р6) |

Подписываются **только** заголовки, которые выставили мы: `host`, `x-amz-*`, `content-type`,
`content-length`, `content-md5`. Всё, что мог добавить движок или прокси, — не подписывается
(ресёрч, следствие 1.3.2).

### 3.5 Presign

Подпись уезжает в query, тело не участвует (`botocore-auth.py:722`, `:810`).

| Параметр | Значение |
|---|---|
| `X-Amz-Algorithm` | `AWS4-HMAC-SHA256` |
| `X-Amz-Credential` | `<ak>/<yyyyMMdd>/<region>/s3/aws4_request` |
| `X-Amz-Date` | метка времени |
| `X-Amz-Expires` | секунды; по умолчанию 3600, максимум 604800 |
| `X-Amz-SignedHeaders` | обычно `host` |
| `X-Amz-Security-Token` | при временных ключах |
| `X-Amz-Signature` | **дописывается последним и в canonical query не входит** (`botocore-auth.py:787`) |

`PAYLOAD_HASH` в canonical request — всегда `UNSIGNED-PAYLOAD`.

Ошибка `X-Amz-Expires` больше 604800 — наша, до отправки; сервер здесь не участвует, ссылку
некому проверить, пока по ней не пойдут.

---

## 4. Операции

Ниже — только то, что реализуется в v1. Полный список параметров каждой операции — в
`s3-service-2.json`, здесь его копия не заводится (иначе разъедется).

### 4.1 put

```
PUT /<bucket>/<key>
Content-Length: <n>          обязателен
Content-Type: <type>         если задан
x-amz-content-sha256: UNSIGNED-PAYLOAD | <hex>
```

`s3-service-2.json:1353`. Успех — `200 OK`, `ETag` в заголовке ответа.

`Content-Length` обязателен: без него libcurl уйдёт в chunked, а сервер ответит
`411 MissingContentLength` (`s3-service-2.json:4768`, ресёрч, следствие 1.6.1). Это не цитата из
модели, а наблюдение: тест шлёт тело без длины через presign-ссылку и получает такой ответ от
MinIO (`S3ClientE2eTest`).

Поэтому длина — обязательный параметр `put`, а не поле со значением по умолчанию.

### 4.2 get

```
GET /<bucket>/<key>
Range: bytes=<from>-<to>     если задан
```

`s3-service-2.json:717`. Успех — `200 OK`, при `Range` — `206 Partial Content`. Тело отдаётся
потоком, целиком в память не читается.

### 4.3 delete

```
DELETE /<bucket>/<key>
```

`s3-service-2.json:329`. Успех — **204 No Content**, тела нет.

Удаление несуществующего ключа в S3 **успешно** — это не ошибка. Тест обязан это закреплять,
потому что интуиция говорит обратное.

### 4.4 head

```
HEAD /<bucket>/<key>
```

`s3-service-2.json:878`. Успех — `200 OK` с `Content-Length`, `ETag`, `Last-Modified`,
`Content-Type`, пользовательскими `x-amz-meta-*`. Тела нет по определению метода.

**Ошибка тоже без тела.** `404` не несёт XML с `<Code>NoSuchKey</Code>`. Кода в исключении не
будет вовсе — `S3Exception.code` останется `null`, а известен только статус. Подставить туда
`NoSuchKey` нельзя: **на отсутствующий бакет `HEAD` отвечает точно так же**, и различить их
нечем. Проверено живыми запросами к MinIO (`S3ClientE2eTest`), а не выведено из документации.

### 4.5 list

```
GET /<bucket>?list-type=2&encoding-type=url[&prefix=][&delimiter=][&max-keys=][&continuation-token=][&start-after=]
```

`s3-service-2.json:1014`, параметры — `shapes.ListObjectsV2Request.members`.

`encoding-type=url` отправляется **всегда**, не по флагу: без него ключ с байтом 0x01 ломает XML
на стороне сервера (`shapes.EncodingType.documentation`, ресёрч, следствие 1.5.3).

Что декодируется в ответе: `Key`, верхнеуровневые `Prefix`, `Delimiter`, `StartAfter` и `Prefix`
внутри `CommonPrefixes`. Список взят из **кода** эталонной реализации
(`botocore/handlers.py`, `decode_list_object_v2`), а не из её комментария: комментарий называет ещё
и `ContinuationToken`, а код его не трогает — и прав код, потому что токен непрозрачный и уезжает
обратно дословно.

> **Кодирование ответа — по-формному, а не по RFC 3986.** Пробел в ключе возвращается как `+`, а не
> как `%20`, при том что в пути запроса тот же пробел кодируется как `%20`. Проверено живым
> запросом; эталонная реализация декодирует эти ответы через `unquote_plus`
> (`botocore/compat.py:62`). Неоднозначности нет: литеральный `+` не входит в unreserved и приезжает
> как `%2B`. В библиотеке это `uriDecode(value, plusIsSpace = true)`.

Ответ — `ListBucketResult`; поля — `shapes.ListObjectsV2Output.members`. Страница считается
последней по `<IsTruncated>false</IsTruncated>`; следующая берётся по `NextContinuationToken`.

`KeyCount` — число элементов **на странице**, не всего в бакете.

### 4.6 multipart

| Шаг | Запрос | Успех |
|---|---|---|
| create | `POST /<bucket>/<key>?uploads` | `200`, `<UploadId>` в теле (`s3-service-2.json:108`) |
| part | `PUT /<bucket>/<key>?partNumber=<n>&uploadId=<id>` | `200`, `ETag` **в заголовке** (`:1596`) |
| complete | `POST /<bucket>/<key>?uploadId=<id>` + XML | `200` — см. ниже (`:32`) |
| abort | `DELETE /<bucket>/<key>?uploadId=<id>` | `204` (`:18`) |

Тело `complete`:

```xml
<CompleteMultipartUpload>
  <Part><PartNumber>1</PartNumber><ETag>"..."</ETag></Part>
  <Part><PartNumber>2</PartNumber><ETag>"..."</ETag></Part>
</CompleteMultipartUpload>
```

Части — строго по возрастанию `PartNumber`, иначе `InvalidPartOrder` (400). Кавычки в `ETag`
сохраняются как пришли.

Ограничения:

| Ограничение | Где проверено |
|---|---|
| `partNumber` — от 1 до 10 000 включительно | `s3-service-2.json:1604` |
| все части кроме последней — не меньше минимального размера, иначе `EntityTooSmall` (400) | `s3-service-2.json:32` |
| часть с уже использованным номером **перезаписывает** предыдущую | `s3-service-2.json:1604` |

> **Минимальный размер части — 5 МиБ** — общеизвестная величина из
> `docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html`; в модели она не записана, модель
> отсылает к этой странице. **Гипотеза, проверить в вехе multipart** тестом, который льёт две
> части по 1 МиБ и ждёт `EntityTooSmall`.

**Грабля `complete`.** «A 200 OK response can contain either a success or an error»
(`s3-service-2.json:32`). Успех определяется **не статусом**, а корневым элементом тела:

| Корневой элемент | Что это |
|---|---|
| `CompleteMultipartUploadResult` | успех |
| `Error` | ошибка, несмотря на 200 |

Ошибки `complete`: `EntityTooSmall`, `InvalidPart`, `InvalidPartOrder`, `NoSuchUpload` — все 400.

---

## 5. Ошибки

Ответ с телом — XML:

```xml
<Error>
  <Code>NoSuchKey</Code>
  <Message>The specified key does not exist.</Message>
  <RequestId>...</RequestId>
  <HostId>...</HostId>
</Error>
```

Ответ без тела — `HEAD` (раздел 4.4) и часть S3-совместимых хранилищ. В этом случае несём то,
что есть: HTTP-статус и заголовки `x-amz-request-id`, `x-amz-id-2`. Без этой пары поддержка AWS
не станет разбирать обращение, поэтому они попадают в исключение всегда, а не только когда тело
разобралось.

Отдельно: при `SignatureDoesNotMatch` S3 присылает в теле **свой** canonical request. Исключение
обязано нести и его, и наш — построчное сравнение находит причину сразу (ресёрч, Риск 4).

Коды, которые точно понадобятся: `NoSuchKey`, `NoSuchBucket`, `NoSuchUpload`, `AccessDenied`,
`SignatureDoesNotMatch`, `EntityTooSmall`, `InvalidPart`, `InvalidPartOrder`,
`MissingContentLength`, `SlowDown`, `InternalError`. Полный список — в `s3-service-2.json`,
сюда не копируется.
