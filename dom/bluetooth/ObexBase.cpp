/* -*- Mode: c++; c-basic-offset: 2; indent-tabs-mode: nil; tab-width: 40 -*- */
/* vim: set ts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "ObexBase.h"

BEGIN_BLUETOOTH_NAMESPACE

int
AppendHeaderName(uint8_t* retBuf, const char* name, int length)
{
  int headerLength = length + 3;

  retBuf[0] = ObexHeaderId::Name;
  retBuf[1] = (headerLength & 0xFF00) >> 8;
  retBuf[2] = headerLength & 0x00FF;

  memcpy(&retBuf[3], name, length);

  return headerLength;
}

int
AppendHeaderBody(uint8_t* retBuf, uint8_t* data, int length)
{
  int headerLength = length + 3;

  retBuf[0] = ObexHeaderId::Body;
  retBuf[1] = (headerLength & 0xFF00) >> 8;
  retBuf[2] = headerLength & 0x00FF;

  memcpy(&retBuf[3], data, length);

  return headerLength;
}

int
AppendHeaderLength(uint8_t* retBuf, int objectLength)
{
  retBuf[0] = ObexHeaderId::Length;
  retBuf[1] = (objectLength & 0xFF000000) >> 24;
  retBuf[2] = (objectLength & 0x00FF0000) >> 16;
  retBuf[3] = (objectLength & 0x0000FF00) >> 8;
  retBuf[4] = objectLength & 0x000000FF;

  return 5;
}

int
AppendHeaderConnectionId(uint8_t* retBuf, int connectionId)
{
  retBuf[0] = ObexHeaderId::ConnectionId;
  retBuf[1] = (connectionId & 0xFF000000) >> 24;
  retBuf[2] = (connectionId & 0x00FF0000) >> 16;
  retBuf[3] = (connectionId & 0x0000FF00) >> 8;
  retBuf[4] = connectionId & 0x000000FF;

  return 5;
}

void
SetObexPacketInfo(uint8_t* retBuf, uint8_t opcode, int packetLength)
{
  retBuf[0] = opcode;
  retBuf[1] = (packetLength & 0xFF00) >> 8;
  retBuf[2] = packetLength & 0x00FF;
}

int
ParseHeadersAndFindBody(uint8_t* aHeaderStart,
                        int aTotalLength,
                        ObexHeaderSet* aRetHandlerSet)
{
  uint8_t* ptr = aHeaderStart;

  while (ptr - aHeaderStart < aTotalLength) {
    ObexHeaderId headerId = (ObexHeaderId)*ptr;

    if (headerId == ObexHeaderId::Body ||
        headerId == ObexHeaderId::EndOfBody) {
      return ptr - aHeaderStart;
    }

    ++ptr;

    int contentLength = 0;
    uint8_t highByte, lowByte;

    // Defined in 2.1 OBEX Headers, IrOBEX 1.2
    switch (headerId >> 6)
    {
      case 0x00:
        // NULL terminated Unicode text, length prefixed with 2-byte
        // unsigned integer.
      case 0x01:
        // byte sequence, length prefixed with 2 byte unsigned integer.
        highByte = *ptr++;
        lowByte = *ptr++;
        contentLength = (((int)highByte << 8) | lowByte) - 3;
        break;

      case 0x02:
        // 1 byte quantity
        contentLength = 1;
        break;

      case 0x03:
        // 4 byte quantity
        contentLength = 4;
        break;
    }

    uint8_t* content = new uint8_t[contentLength];
    memcpy(content, ptr, contentLength);
    aRetHandlerSet->AddHeader(new ObexHeader(headerId, contentLength, content));

    ptr += contentLength;
  }

  return -1;
}

END_BLUETOOTH_NAMESPACE
