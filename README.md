# RoutineBridge

**Русский | [English](#english)**

## О приложении

RoutineBridge связывает пользовательские входы с ручными сценариями Samsung Modes and Routines.

Приложение получает из Samsung список доступных ручных сценариев — это выходы, которыми можно управлять. Пользователь создаёт вход, связывает его с нужным ручным сценарием и выбирает действие, которое должно выполняться при запуске этого входа.

## Возможности

- Загрузка доступных ручных сценариев Samsung в качестве выходов.
- Создание пользовательских входов и привязка каждого из них к ручному сценарию.
- Три действия для выбранного сценария: включить, выключить или переключить.
- Определение сценариев по UUID, а не по фиксированным номерам слотов.
- Создание динамического ярлыка Android для каждого настроенного входа.
- Пользовательские названия входов.
- Группировка входов по связанным сценариям.
- Сворачивание и разворачивание групп.
- Редактирование настроек входа непосредственно в списке.
- Интерфейс на русском и английском языках.

## Как это работает

1. RoutineBridge получает из Samsung список доступных ручных сценариев. Они используются как выходы.
2. Пользователь создаёт вход, выбирает для него ручной сценарий Samsung и задаёт действие.
3. Для каждого настроенного входа приложение публикует динамический ярлык Android.
4. При запуске ярлыка RoutineBridge проверяет состояние выбранного сценария и выполняет необходимое действие.
5. Приложение проверяет результат выполнения по состоянию сценария в Samsung.

**Вход и выход — разные стороны связи.** Вход определяет, когда запускать действие. Выход — это ручной сценарий Samsung, над которым выполняется действие. Само выполнение запускается через соответствующий ярлык Android.

## Требования

- Android 10 или новее.
- Устройство Samsung с Modes and Routines.
- Root-доступ.

## Ограничения

Работа зависит от внутренних механизмов Samsung и доступности `ExternalRoutineContentProvider`. Формат и доступность данных могут меняться в будущих версиях One UI.

Для выполнения действия выбранный сценарий должен быть включён в Samsung, а его состояние должно быть доступно для проверки.

Количество динамических ярлыков ограничено системным лимитом Android `maxShortcutCountPerActivity`. Если настроенных входов больше этого лимита, приложение публикует только допустимое количество ярлыков и сообщает об ограничении. Сам лимит может различаться на разных устройствах.

RoutineBridge не является приложением Samsung Electronics и не связан с Samsung официально.

---

<a id="english"></a>

# RoutineBridge

## About

RoutineBridge connects user-defined inputs to Samsung Modes and Routines manual routines.

The app retrieves the available Samsung manual routines. These routines are the outputs that can be controlled. The user creates an input, associates it with a manual Samsung routine, and selects the action to perform when that input is launched.

## Features

- Load available Samsung manual routines as outputs.
- Create user-defined inputs and associate each one with a manual routine.
- Three actions for the selected routine: Enable, Disable, and Toggle.
- Identify routines by UUID rather than fixed numbered slots.
- Create an Android dynamic shortcut for each configured input.
- Custom input names.
- Group inputs by their associated routines.
- Collapse and expand groups.
- Edit input settings directly in the list.
- Russian and English interface.

## How it works

1. RoutineBridge retrieves the available Samsung manual routines. These are used as outputs.
2. The user creates an input, selects a Samsung manual routine, and chooses an action.
3. The app publishes an Android dynamic shortcut for each configured input.
4. When the shortcut is launched, RoutineBridge checks the selected routine's state and performs the required action.
5. The app verifies the result by checking the routine's state in Samsung.

**Inputs and outputs are separate parts of the mapping.** An input determines when an action is launched. The output is the Samsung manual routine on which the action is performed. Execution is initiated through the corresponding Android shortcut.

## Requirements

- Android 10 or later.
- A Samsung device with Modes and Routines.
- Root access.

## Limitations

Operation depends on Samsung's internal mechanisms and the availability of `ExternalRoutineContentProvider`. The format and availability of its data may change in future One UI versions.

The selected routine must be enabled in Samsung, and its state must be available for verification.

The number of dynamic shortcuts is limited by Android's `maxShortcutCountPerActivity` system limit. If the number of configured inputs exceeds this limit, the app publishes only the allowed number of shortcuts and reports the limitation. The limit may vary between devices.

RoutineBridge is not an official Samsung Electronics application and is not affiliated with Samsung.
