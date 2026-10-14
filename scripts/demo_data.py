#!/usr/bin/env python3
"""Демо-данные для CRM: лиды, переписка, история и время ответа.

Запуск из корня проекта (база из docker compose должна работать):
    python3 scripts/demo_data.py          # добавить ~150 демо-лидов
    python3 scripts/demo_data.py --clear  # удалить всех демо-лидов

Демо-лиды отличаются Telegram ID: 9100000000–9199999999. Настоящих лидов скрипт не трогает.
Берёт статусы, теги и активных пользователей, которые есть в базе сейчас.
"""
import random
import subprocess
import sys
from datetime import datetime, timedelta, timezone

CONTAINER = "vibe-crm-postgres"
ID_FROM, ID_TO = 9100000000, 9199999999


def psql(sql, fetch=False):
    cmd = ["docker", "exec", "-i", CONTAINER, "psql", "-q", "-U", "crm", "-d", "crm", "-v", "ON_ERROR_STOP=1"]
    if fetch:
        cmd += ["-At", "-F", "\t"]
    out = subprocess.run(cmd, input=sql, text=True, capture_output=True)
    if out.returncode != 0:
        sys.exit(out.stderr)
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line]


def q(s):
    return "null" if s is None else "'" + str(s).replace("'", "''") + "'"


def ts(d):
    return q(d.isoformat())


def clear():
    psql(f"delete from leads where telegram_user_id between {ID_FROM} and {ID_TO};")
    print("Демо-лиды удалены")


FEMALE = ["Анна", "Алина", "Екатерина", "Софья", "Ольга", "Вера", "Ирина", "Марина", "Юлия", "Наталья", "Ксения",
          "Елена", "Дарья", "Полина", "Виктория"]
MALE = ["Игорь", "Тимур", "Дмитрий", "Максим", "Руслан", "Никита", "Павел", "Сергей", "Артём", "Кирилл", "Денис",
        "Роман", "Глеб", "Илья", "Егор"]
# фамилия в мужской форме; женская — по окончанию
LAST = ["Смирнов", "Петров", "Морозов", "Галиев", "Волков", "Орлов", "Лебедев", "Кузнецов", "Ахметов", "Новиков",
        "Соколов", "Зайцев", "Попов", "Васильев", "Фёдоров", "Михайлов", "Белов", None, None, None]


def person(rng):
    female = rng.random() < 0.5
    first = rng.choice(FEMALE if female else MALE)
    last = rng.choice(LAST)
    if last and female:
        last += "а"
    return first, last


FIRST_IN = [
    "Здравствуйте! Сколько стоит доставка в {city}?", "Интересует оптовый прайс от {n} шт.",
    "Добрый день, есть ли в наличии?", "Срочно нужна консультация по заказу", "Пришлите, пожалуйста, договор",
    "Нужна партия на {n} штук для офиса. Какие сроки?", "Хочу ещё такой же, как в прошлый раз",
    "А можно посмотреть вживую?", "Какие есть способы оплаты?", "Работаете с юрлицами?",
    "Подскажите по гарантии", "Можно ли сделать скидку при заказе от {n} шт.?", "Увидел вас в канале, расскажите подробнее",
]
FOLLOW_IN = ["Нужно до пятницы, успеете?", "Жду счёт на юрлицо", "Оплатила, спасибо!", "Пока дорого, вернусь позже",
             "Всё получили, всё отлично", "А доставка сколько займёт?", "Ок, подумаю", "Отправьте реквизиты",
             "Можно созвониться завтра?", "Спасибо за быстрый ответ!", "[Фото] Вот такой вариант нужен"]
OUT = ["Добрый день! Подготовлю для вас расчёт.", "Здравствуйте! Да, всё в наличии.",
       "Партия такого объёма — 5–7 рабочих дней.", "Отправил прайс, посмотрите, пожалуйста.",
       "Счёт выставлен, проверьте почту.", "Да, работаем с юрлицами по договору.", "Доставка 2–3 дня.",
       "Конечно, наберу вам завтра в 11:00.", "Скидка 7% от 50 штук."]
CITIES = ["Казань", "Самару", "Екатеринбург", "Новосибирск", "Пермь", "Тверь"]


def seed(count=150):
    rng = random.Random(42)
    statuses = psql("select id, name, won from statuses order by position", fetch=True)
    tags = [row[0] for row in psql("select id from tags order by id", fetch=True)]
    users = psql("select id, display_name from users where active order by id", fetch=True)
    if not statuses:
        sys.exit("В базе нет статусов")
    existing = int(psql(f"select coalesce(max(telegram_user_id) - {ID_FROM}, 0) from leads "
                        f"where telegram_user_id between {ID_FROM} and {ID_TO}", fetch=True)[0][0])
    now = datetime.now(timezone.utc)
    sources = ["CHANNEL", "GROUP", "ADS", "REFERRAL", "OTHER", None]
    source_w = [30, 18, 22, 12, 5, 13]
    status_w = [max(1, 30 - 4 * i) for i in range(len(statuses))]
    sql = ["begin;"]
    for i in range(1, count + 1):
        tg = ID_FROM + existing + i
        # больше лидов в последние недели
        created = now - timedelta(days=90 * rng.random() ** 1.6, minutes=rng.randint(0, 1440))
        status = rng.choices(statuses, weights=status_w)[0]
        first, last = person(rng)
        username = None if rng.random() < 0.2 else f"{first.lower()[:4]}_{rng.randint(10, 999)}_demo".translate(
            str.maketrans("абвгдеёжзийклмнопрстуфхцчшщъыьэюя", "abvgdeejziiklmnoprstufhccss_y_eua"))
        manager = rng.choice(users + [None, None]) if status[0] != statuses[0][0] or rng.random() < 0.3 else None
        source = rng.choices(sources, weights=source_w)[0]
        phone = f"+79{rng.randint(10, 99)}{rng.randint(1000000, 9999999)}" if rng.random() < 0.35 else None
        lead_tags = rng.sample(tags, k=min(len(tags), rng.choices([0, 1, 2], weights=[45, 40, 15])[0]))

        # переписка: входящие и ответы с задержкой
        t = created
        msgs = []
        mid = 0
        responses = []
        for turn in range(rng.randint(1, 4)):
            text = (rng.choice(FIRST_IN) if turn == 0 else rng.choice(FOLLOW_IN)).format(
                city=rng.choice(CITIES), n=rng.choice([20, 50, 100, 120, 300]))
            mid += 1
            msgs.append((mid, "IN", text, t, None))
            waiting_since = t
            if t > now - timedelta(minutes=30) or rng.random() < 0.15:
                break
            wait = timedelta(minutes=rng.choice([2, 5, 8, 12, 20, 35, 60, 180, 600]) * rng.uniform(0.6, 1.4))
            t = t + wait
            if t > now:
                break
            author = manager or (rng.choice(users) if rng.random() < 0.5 else None)
            mid += 1
            msgs.append((mid, "OUT", rng.choice(OUT), t, author))
            responses.append((author, int(wait.total_seconds()), t))
            t = t + timedelta(hours=rng.uniform(0.5, 30))
            if t > now:
                break
        last_msg = msgs[-1]
        last_in = max(m[3] for m in msgs if m[1] == "IN")
        awaiting = msgs[-1][3] if msgs[-1][1] == "IN" else None

        sql.append(
            "insert into leads (telegram_user_id, telegram_chat_id, first_name, last_name, username, phone, status_id,"
            " manager_id, source, created_at, last_message_at, last_message_text, last_incoming_at, awaiting_reply_since)"
            f" values ({tg}, {tg}, {q(first)}, {q(last)}, {q(username)}, {q(phone)}, {status[0]},"
            f" {manager[0] if manager else 'null'}, {q(source)}, {ts(created)}, {ts(last_msg[3])}, {q(last_msg[2])},"
            f" {ts(last_in)}, {ts(awaiting) if awaiting else 'null'});")
        lead = f"(select id from leads where telegram_user_id = {tg})"
        for tag in lead_tags:
            sql.append(f"insert into lead_tags (lead_id, tag_id) values ({lead}, {tag});")
        for m_id, direction, text, at, author in msgs:
            sql.append("insert into messages (lead_id, telegram_message_id, direction, text, sent_at, author_id) values"
                       f" ({lead}, {m_id}, '{direction}', {q(text)}, {ts(at)}, {author[0] if author else 'null'});")
        for author, waited, at in responses:
            sql.append("insert into response_events (lead_id, author_id, waited_seconds, answered_at) values"
                       f" ({lead}, {author[0] if author else 'null'}, {waited}, {ts(at)});")
        sql.append(f"insert into lead_events (lead_id, type, created_at) values ({lead}, 'CREATED', {ts(created)});")
        actor = manager or (users[0] if users else None)
        step = created + timedelta(minutes=rng.randint(5, 120))
        if manager and step < now:
            sql.append("insert into lead_events (lead_id, actor_id, type, new_value, created_at) values"
                       f" ({lead}, {actor[0]}, 'MANAGER_CHANGED', {q(manager[1])}, {ts(step)});")
        if status[0] != statuses[0][0] and actor:
            step = step + timedelta(hours=rng.uniform(1, 48))
            sql.append("insert into lead_events (lead_id, actor_id, type, old_value, new_value, created_at) values"
                       f" ({lead}, {actor[0]}, 'STATUS_CHANGED', {q(statuses[0][1])}, {q(status[1])},"
                       f" {ts(min(step, now))});")
    sql.append("commit;")
    psql("\n".join(sql))
    print(f"Добавлено демо-лидов: {count}")


if __name__ == "__main__":
    clear() if "--clear" in sys.argv else seed()
