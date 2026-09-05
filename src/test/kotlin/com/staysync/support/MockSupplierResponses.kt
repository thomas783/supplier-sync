package com.staysync.support

/**
 * 어댑터 테스트용 공급사 응답 JSON 픽스처 — 각 공급사 API 문서의 응답 구조를 따른다.
 * A: 날짜별 net + tax / HTTP 상태 코드로 실패.
 * B: 전체 총액 gross / 항상 200 + resultCode 로 실패.
 */
object MockSupplierResponses {

    val A_HOTELS = """
        {
          "items": [
            { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
              "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 } ] },
            { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
              "roomTypes": [ { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 } ] }
          ]
        }
    """.trimIndent()

    val A_AVAILABILITY = """
        {
          "items": [
            { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul", "roomTypeCode": "DLX-TWN",
              "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
              ] },
            { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay", "roomTypeCode": "STD-DBL",
              "roomTypeName": "Standard Double", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
              "dailyRates": [
                { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
                { "date": "2026-09-02", "remainingRooms": 0, "nightlyRate": 99000, "taxAmount": 9900 },
                { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 88000, "taxAmount": 8800 }
              ] }
          ]
        }
    """.trimIndent()

    /** A 장애 — HTTP 503. */
    val A_ERROR = """{"error":"SERVICE_UNAVAILABLE","message":"temporarily unavailable"}"""

    val B_PROPERTIES = """
        {
          "resultCode": "0000", "resultMessage": "SUCCESS",
          "data": { "items": [
            { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
              "rooms": [ { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 } ] }
          ] }
        }
    """.trimIndent()

    val B_SEARCH = """
        {
          "resultCode": "0000", "resultMessage": "SUCCESS",
          "data": { "items": [
            { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul", "roomId": "R-401",
              "roomName": "Deluxe Twin Room", "maxOccupancy": 2, "breakfastIncluded": true, "currency": "KRW",
              "totalPrice": 452000, "taxIncluded": true,
              "inventory": [
                { "date": "2026-09-01", "remainingRooms": 3 },
                { "date": "2026-09-02", "remainingRooms": 1 },
                { "date": "2026-09-03", "remainingRooms": 5 }
              ] }
          ] }
        }
    """.trimIndent()

    /** B 장애 — HTTP 는 200 이지만 resultCode 로 실패. */
    val B_ERROR = """{"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}"""
}
