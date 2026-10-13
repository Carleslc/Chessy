from typing import Union

import pytz

from datetime import date, datetime

PGN_DATETIME_FORMAT = '%Y.%m.%d %H:%M:%S'

def pgn_parse_datetime(date: str, time: str) -> datetime:
  return datetime.strptime(f'{date} {time}', PGN_DATETIME_FORMAT)

def pgn_utc_to_local_datetime(utcdate: str, utctime: str) -> datetime:
  dt = pytz.utc.localize(datetime.strptime(f'{utcdate} {utctime}', PGN_DATETIME_FORMAT))
  return dt.astimezone()

def pgn_datetime(headers: dict[str, str]) -> Union[datetime, date, str]:
  h_date = headers.get('UTCDate')
  h_time = headers.get('UTCTime')

  dt = None

  if h_date and h_time:
    dt = pgn_utc_to_local_datetime(h_date, h_time)
  else:
    h_date = headers.get('Date')
    h_time = headers.get('Time')
    if h_date:
      if h_time:
        dt = pgn_parse_datetime(h_date, h_time)
      else:
        year, month, day = h_date.split('.')
        try:
          dt = date(int(year), int(month), int(day))
        except ValueError:
          return h_date
  
  return dt
