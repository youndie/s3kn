---
id: feature-object-io
title: Чтение и запись объектов
type: feature
status: active
owner: unassigned
involved_services:
  - s3-client
  - s3-sigv4
  - s3-core
api:
  - protocol-s3
tags: [v1]
---

# Чтение и запись объектов

## 1. Суть

Положить объект, забрать его, узнать о нём, удалить. Четыре операции, которыми исчерпывается
работа с одиночным объектом: `put`, `get`, `head`, `delete`.

Тело можно отдать как `ByteArray`, если оно уже в памяти, или как поток с заявленной длиной, если
оно велико. Читается тело всегда потоком: пятигигабайтный объект не собирается в памяти ни на
входе, ни на выходе.

## 2. Бизнес-ограничения

- Длина тела при потоковой записи — **обязательный параметр**. Без неё движок уходит в chunked, и
  сервер отвечает `411 MissingContentLength`.
- Поток поверх `http` подписывается как `UNSIGNED-PAYLOAD` и **отвергается**, если не включён
  `allowUnsignedPayloadOverHttp`: без TLS подпись заголовков не защищает тело.
- Удаление отсутствующего ключа — **успех**, а не ошибка.
- `HEAD` не различает отсутствующий ключ и отсутствующий бакет: у ответа нет тела, различать нечем.
- Ключ с сегментом `.` или `..` отвергается до отправки.

## 4. Якоря кода

| Модуль | Код |
|---|---|
| `:s3-client` | `s3-client/src/commonMain/kotlin/io/github/youndie/s3/S3Client.kt` |
| `:s3-client` | `ParsedError.kt` — разбор `<Error>` |

## 5. Сценарии

| Сценарий | Ожидание | Где проверено |
|---|---|---|
| Положить объект и прочитать его | тело совпадает, `Content-Type` сохранён | `S3ClientE2eTest`, `stores an object and reads it back` |
| Положить поток с заявленной длиной | объект на месте целиком | `S3ClientE2eTest`, `stores an object streamed with a stated length` |
| Отправить тело без длины | `411 MissingContentLength` | `S3ClientE2eTest`, `is answered with 411 when a body arrives without a stated length` |
| Прочитать диапазон | приходят ровно запрошенные байты | `S3ClientE2eTest`, `reads part of an object with a range` |
| `get` отсутствующего ключа | `404`, код `NoSuchKey` из тела | `S3ClientE2eTest`, `names the error of a get that found nothing` |
| `head` отсутствующего ключа | `404`, кода нет | `S3ClientE2eTest`, `reports a missing key as a 404 that carries no error code` |
| Удалить и убедиться, что нет | `head` даёт `404` | `S3ClientE2eTest`, `removes an object and then reports it gone` |
| Удалить то, чего нет | успех | `S3ClientE2eTest`, `treats removing something that is not there as success` |
| Ключи с пробелом, `+`, `~`, `%`, кириллицей, эмодзи | приезжают и читаются | `S3ClientE2eTest`, `handles the keys that break a naive encoder` |
