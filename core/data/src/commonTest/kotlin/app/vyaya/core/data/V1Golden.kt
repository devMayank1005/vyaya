package app.vyaya.core.data

/** core/backup's golden v1 file (the format is frozen), for restoring a first-version backup into the database. */
val V1_GOLDEN_JSON = """
{
  "format": "vyaya-backup",
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "createdOn": "android",
  "exportedAt": "2026-10-27T12:00:00Z",
  "currency": "INR",
  "settings": {
    "monthStartDay": 1,
    "startDayChanges": [
      {
        "effectiveFrom": "2026-04-01",
        "transitionEnd": "2026-04-25",
        "startDay": 25
      }
    ],
    "timezone": "Asia/Kolkata",
    "alertsEnabled": true
  },
  "accounts": [
    {
      "id": "00000000-0000-4000-8000-000000000001",
      "name": "HDFC Salary",
      "kind": "BANK",
      "mask": "1234",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "categories": [
    {
      "id": "00000000-0000-4000-8000-000000000002",
      "name": "Food",
      "kind": "EXPENSE",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    },
    {
      "id": "00000000-0000-4000-8000-000000000003",
      "name": "Groceries",
      "kind": "EXPENSE",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": true
    }
  ],
  "entries": [
    {
      "id": "00000000-0000-4000-8000-000000000004",
      "type": "EXPENSE",
      "amountPaise": 12050,
      "occurredAt": "2026-10-26T13:05:00Z",
      "timezone": "Asia/Kolkata",
      "accountId": "00000000-0000-4000-8000-000000000001",
      "toAccountId": null,
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "payee": "Swiggy",
      "note": "lunch",
      "tags": [
        "office"
      ],
      "source": "NOTIFICATION",
      "upiRef": "429112345678",
      "splits": [
        {
          "categoryId": "00000000-0000-4000-8000-000000000002",
          "amountPaise": 10050
        },
        {
          "categoryId": "sys.saved",
          "amountPaise": 2000
        }
      ],
      "linkedEntryId": null,
      "createdAt": "2026-10-27T10:00:00Z",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "recurring": [
    {
      "id": "00000000-0000-4000-8000-000000000007",
      "type": "EXPENSE",
      "amountPaise": 11900,
      "dayOfMonth": 5,
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "accountId": "00000000-0000-4000-8000-000000000001",
      "note": "Spotify",
      "paused": false,
      "startsOn": "2026-01-05",
      "lastGeneratedOn": "2026-10-05",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "people": [
    {
      "id": "00000000-0000-4000-8000-000000000005",
      "name": "Rahul",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "ledger": [
    {
      "id": "00000000-0000-4000-8000-000000000006",
      "personId": "00000000-0000-4000-8000-000000000005",
      "direction": "LENT",
      "amountPaise": 30000,
      "date": "2026-10-20",
      "note": "Dinner",
      "settled": false,
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "budgets": [
    {
      "id": "00000000-0000-4000-8000-000000000008",
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "limitPaise": 500000,
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ],
  "merchantRules": [
    {
      "id": "00000000-0000-4000-8000-000000000009",
      "payee": "swiggy",
      "categoryId": "00000000-0000-4000-8000-000000000002",
      "updatedAt": "2026-10-27T10:00:00Z",
      "deleted": false
    }
  ]
}
""".trim()
