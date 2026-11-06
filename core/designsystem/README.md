# core/designsystem

Shared Android Views, XML resources and light, dark and Monet themes. Use
`PreferenceRow` for settings, `FormSheet` or `choiceSheet` for bottom sheets
(`CouiBottomSheet`, a View port of the COUI 1.1.0 panel), `Screen.showHelp` for
COUI help tooltips (`CouiTooltip`), `ListEntranceLayout` for rows prepended to a
live list, and `Screen` to bind repository flows to the
activity lifecycle.

Feature modules own their layouts and use View Binding to render state and handle input.
