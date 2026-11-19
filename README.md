# RoutineBridge

**Русский | [English](#english)**

## О приложении

RoutineBridge связывает входные события с действиями Samsung Modes and Routines.

Приложение позволяет использовать доступные источники событий в качестве входов и запускать соответствующие ручные сценарии Samsung. Основной сценарий использования — управление ручными сценариями через автоматические сценарии и другие доступные входы.

## Возможности

- Подключение доступных приложению источников событий в качестве входов.
- Использование автоматических сценариев Samsung как основных источников событий.
- Сопоставление входов с доступными выходными сценариями Samsung.
- Определение сценариев по UUID, а не по фиксированным номерам слотов.
- Три действия для выходного сценария: включить, выключить или переключить.
- Пользовательские названия входов.
- Группировка входов по связанным сценариям.
- Сворачивание и разворачивание групп.
- Редактирование настроек входа непосредственно в списке.
- Создание ярлыков для запуска настроенных действий.
- Интерфейс на русском и английском языках.

## Как это работает

1. RoutineBridge получает доступные входные источники.
2. Пользователь выбирает вход и связывает его с нужным ручным сценарием Samsung.
3. Для связи сохраняется UUID сценария и выбранное действие.
4. При срабатывании входа RoutineBridge выполняет соответствующее действие над выходным сценарием.

**Вход и выход — разные стороны связи.** Входом может быть автоматический сценарий или другой источник событий, доступный приложению. Управляемыми выходами в текущей реализации являются ручные сценарии Samsung.

Доступность конкретных типов входов зависит от предоставляемых системой и приложениями интерфейсов.

## Требования

- Android 10 или новее.
- Устройство Samsung с Modes and Routines.
- Root-доступ.

## Ограничения

Работа зависит от внутренних механизмов Samsung и доступности `ExternalRoutineContentProvider`. Совместимость с будущими версиями One UI не гарантируется.

RoutineBridge не является приложением Samsung Electronics и не связан с Samsung официально.

## Версия

0.9.1

---

<a id="english"></a>

# RoutineBridge

## About

RoutineBridge connects input events to actions in Samsung Modes and Routines.

The app allows available event sources to be used as inputs and triggers corresponding Samsung manual routines. Its primary use case is controlling manual routines through automated routines and other available inputs.

## Features

- Connect event sources available to the app as inputs.
- Use Samsung automated routines as primary event sources.
- Map inputs to available Samsung output routines.
- Identify routines by UUID rather than fixed numbered slots.
- Three output actions: Enable, Disable, and Toggle.
- Custom input names.
- Group inputs by their associated routines.
- Collapse and expand groups.
- Edit input settings directly in the list.
- Create shortcuts for configured actions.
- Russian and English interface.

## How it works

1. RoutineBridge obtains the available input sources.
2. The user selects an input and maps it to a Samsung manual routine.
3. The mapping stores the routine UUID and the selected action.
4. When the input is triggered, RoutineBridge performs the corresponding action on the output routine.

**Inputs and outputs are separate parts of the mapping.** An input can be an automated routine or another event source available to the app. In the current implementation, Samsung manual routines are the controllable outputs.

The availability of specific input types depends on the interfaces exposed by the system and other apps.

## Requirements

- Android 10 or later.
- A Samsung device with Modes and Routines.
- Root access.

## Limitations

Operation depends on Samsung's internal mechanisms and the availability of `ExternalRoutineContentProvider`. Compatibility with future One UI versions is not guaranteed.

RoutineBridge is not an official Samsung Electronics application and is not affiliated with Samsung.

## Version

0.9.1
