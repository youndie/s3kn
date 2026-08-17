---
id: feature-multipart
title: Многочастная загрузка
type: feature
status: active
owner: unassigned
involved_services:
  - s3-client
api:
  - protocol-s3
tags: [v1]
---

# Многочастная загрузка

## 1. Суть

Положить объект, который не стоит отправлять одним запросом: поток режется на части, части уходят
по нескольку сразу, а сервер собирает из них один объект.

Есть и разобранный вид — `createMultipartUpload`, `uploadPart`, `completeMultipartUpload`,
`abortMultipartUpload`, — если частями управляет вызывающий, и собранный `putMultipart`, если нет.

## 2. Бизнес-ограничения

- Все части кроме последней — **не меньше 5 МиБ**, иначе `EntityTooSmall` на завершении, то есть
  после того, как всё уже загружено.
- Номер части — от 1 до 10 000; выход за границы отвергается до отправки.
- Части перечисляются при завершении **по возрастанию номера**, иначе `InvalidPartOrder`.
- **`complete` может ответить `200` и вложить в тело ошибку.** Успех определяется корневым
  элементом.
- Незавершённая загрузка хранит свои части и тарифицируется. Любая ошибка и любая отмена
  заканчиваются `abort`.
- `putMultipart` держит в памяти не более `partSize × concurrency` байт.

## 4. Якоря кода

| Модуль | Код |
|---|---|
| `:s3-client` | `MultipartUpload.kt` — типы, ограничения, разбор форм |
| `:s3-client` | `S3Client.kt`, `createMultipartUpload`…`putMultipart` |

## 5. Сценарии

| Сценарий | Ожидание | Где проверено |
|---|---|---|
| Загрузить объект частями | объект собран, ETag с суффиксом `-N` | `S3MultipartE2eTest`, `uploads an object in parts and reads it back whole` |
| Две части по 1 МиБ | `EntityTooSmall` | `S3MultipartE2eTest`, `refuses to assemble parts that are too small` |
| Отменить загрузку | объекта нет, завершить нечего | `S3MultipartE2eTest`, `forgets the parts of an aborted upload` |
| `complete` вернул `200` с `<Error>` | ошибка, а не успех | `MultipartUploadTest`, `treats an error inside a 200 response as a failure` |
| Часть упала посередине | уходит `abort` | `MultipartUploadTest`, `aborts the upload when a part fails` |
| Вызывающего отменили | `abort` всё равно уходит | `MultipartUploadTest`, `aborts the upload when the caller is cancelled` |
| Номер части 0 или 10001 | отказ до отправки | `MultipartUploadTest`, `refuses a part number outside the range S3 allows` |
