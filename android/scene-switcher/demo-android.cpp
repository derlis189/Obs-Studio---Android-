// SPDX-License-Identifier: GPL-2.0-or-later
// This harness uses the upstream macro thread, condition and action factories.
// QTimer only observes results and ends the test; it never switches demo scenes.
#include "macro.hpp"
#include "macro-action-factory.hpp"
#include "macro-condition-factory.hpp"
#include "macro-helpers.hpp"
#include "obs-module-helper.hpp"
#include "plugin-state-helpers.hpp"
#include "switcher-data.hpp"
#include <obs-frontend-api.h>
#include <QAction>
#include <QApplication>
#include <QElapsedTimer>
#include <QDir>
#include <QFileInfo>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMainWindow>
#include <QMessageBox>
#include <QSaveFile>
#include <QSignalBlocker>
#include <QTimer>

namespace advss {
namespace {
QString configPath(const char *name)
{
    char *path = obs_module_get_config_path(obs_current_module(), name);
    QString result = QString::fromUtf8(path);
    bfree(path);
    QDir().mkpath(QFileInfo(result).absolutePath());
    return result;
}

struct Demo : QObject {
    OBSSource original, preview;
    OBSScene a, b;
    std::shared_ptr<Macro> toB, toA;
    QTimer timer;
    QElapsedTimer elapsed;
    bool seenB = false, seenReturn = false, finished = false;
    bool studio = false;
    int previousInterval = 300;

    static void onEvent(enum obs_frontend_event event, void *data)
    {
        if (event == OBS_FRONTEND_EVENT_EXIT || event == OBS_FRONTEND_EVENT_SCENE_COLLECTION_CHANGING)
            static_cast<Demo *>(data)->finish("interrupted");
    }

    static std::shared_ptr<Macro> makeMacro(const char *name, const char *target, int seconds)
    {
        auto macro = std::make_shared<Macro>(name);
        auto condition = MacroConditionFactory::Create("timer", macro.get());
        auto action = MacroActionFactory::Create(MacroAction::GetDefaultID().data(), macro.get());
        if (!condition || !action) return {};
        OBSDataAutoRelease data = obs_data_create();
        obs_data_set_bool(data, "enabled", true);
        obs_data_set_int(data, "logic", 0);
        obs_data_set_int(data, "time_constraint", 0);
        obs_data_set_double(data, "seconds", seconds);
        obs_data_set_double(data, "remaining", seconds);
        obs_data_set_bool(data, "oneshot", true);
        condition->Load(data);
        OBSDataAutoRelease actionData = obs_data_create();
        obs_data_set_bool(actionData, "enabled", true);
        obs_data_set_string(actionData, "scene", target);
        obs_data_set_int(actionData, "sceneType", 0);
        obs_data_set_int(actionData, "transitionType", 1); // use current transition
        obs_data_set_bool(actionData, "blockUntilTransitionDone", false);
        action->Load(actionData);
        macro->Conditions().push_back(condition);
        macro->Actions().push_back(action);
        macro->UpdateConditionIndices();
        macro->UpdateActionIndices();
        macro->SetActionTriggerMode(Macro::ActionTriggerMode::MACRO_RESULT_CHANGED);
        macro->SetSkipExecOnStart(false);
        macro->SetPaused(false);
        return macro;
    }

    static OBSScene makeScene(const char *name, const char *colorName, uint32_t color)
    {
        OBSSceneAutoRelease scene = obs_scene_create(name);
        OBSDataAutoRelease data = obs_data_create();
        obs_data_set_int(data, "color", color);
        obs_data_set_int(data, "width", 1280);
        obs_data_set_int(data, "height", 720);
        OBSSourceAutoRelease source = obs_source_create("color_source_v3", colorName, data, nullptr);
        if (source) obs_scene_add(scene, source);
        return scene.Get();
    }

    void finish(const QString &reason, bool restore = true)
    {
        if (finished) return;
        finished = true;
        timer.stop();
        obs_frontend_remove_event_callback(onEvent, this);
        StopPlugin(); // joins the upstream macro thread before touching macros
        const int countB = toB ? toB->RunCount() : 0;
        const int countA = toA ? toA->RunCount() : 0;
        {
            std::lock_guard<std::mutex> lock(switcher->m);
            auto &macros = GetTopLevelMacros();
            macros.erase(std::remove(macros.begin(), macros.end(), toB), macros.end());
            macros.erase(std::remove(macros.begin(), macros.end(), toA), macros.end());
            switcher->interval = previousInterval;
        }
        if (restore) {
            if (original) obs_frontend_set_current_scene(original);
            if (studio && preview) obs_frontend_set_current_preview_scene(preview);
        }
        if (a) obs_source_remove(obs_scene_get_source(a));
        if (b) obs_source_remove(obs_scene_get_source(b));
        a = nullptr;
        b = nullptr;
        toA.reset();
        toB.reset();
        obs_frontend_save();
        QJsonObject report{{"pass", reason == "complete" && seenB && seenReturn && countB == 1 && countA == 1},
                           {"reason", reason}, {"saw_b", seenB}, {"saw_return_a", seenReturn},
                           {"macro_to_b_runs", countB}, {"macro_to_a_runs", countA},
                           {"elapsed_ms", elapsed.elapsed()}, {"stopped", !PluginIsRunning()}};
        QSaveFile file(configPath("android-demo-result.json"));
        if (file.open(QIODevice::WriteOnly)) {
            file.write(QJsonDocument(report).toJson());
            file.commit();
        }
        blog(LOG_INFO, "[adv-ss] Android demo: %s", QJsonDocument(report).toJson(QJsonDocument::Compact).constData());
        deleteLater();
    }

    void start()
    {
        obs_frontend_add_event_callback(onEvent, this);
        original = OBSSourceAutoRelease(obs_frontend_get_current_scene()).Get();
        studio = obs_frontend_preview_program_mode_active();
        preview = OBSSourceAutoRelease(obs_frontend_get_current_preview_scene()).Get();
        a = makeScene("Prueba Director A", "Director azul", 0xffd06020);
        b = makeScene("Prueba Director B", "Director verde", 0xff40b040);
        toB = makeMacro("Prueba temporal A a B", "Prueba Director B", 3);
        toA = makeMacro("Prueba temporal B a A", "Prueba Director A", 6);
        if (!a || !b || !toA || !toB) { finish("setup_failed"); return; }
        {
            std::lock_guard<std::mutex> lock(switcher->m);
            previousInterval = switcher->interval;
            switcher->interval = 300;
            GetTopLevelMacros().push_back(toB);
            GetTopLevelMacros().push_back(toA);
        }
        obs_frontend_set_current_scene(obs_scene_get_source(a));
        elapsed.start();
        connect(&timer, &QTimer::timeout, this, [this] {
            if (obs_frontend_streaming_active() || obs_frontend_recording_active()) {
                finish("output_started"); return;
            }
            OBSSourceAutoRelease current = obs_frontend_get_current_scene();
            if (current == obs_scene_get_source(b)) seenB = true;
            else if (current == obs_scene_get_source(a)) {
                if (seenB) seenReturn = true;
            } else { finish("user_changed_scene", false); return; }
            if (elapsed.elapsed() >= 8500) finish("complete");
        });
        timer.start(100);
        StartPlugin();
    }
};

void runDemo()
{
    OBSSourceAutoRelease existingA = obs_get_source_by_name("Prueba Director A");
    OBSSourceAutoRelease existingB = obs_get_source_by_name("Prueba Director B");
    if (PluginIsRunning() || !GetTopLevelMacros().empty() || existingA || existingB ||
        obs_frontend_streaming_active() || obs_frontend_recording_active()) {
        blog(LOG_WARNING, "[adv-ss] Demo refused: stop automation/outputs; demo names must be free and macro list empty");
        return;
    }
    auto demo = new Demo;
    demo->setParent(static_cast<QObject *>(obs_frontend_get_main_window()));
    demo->start();
}

const bool registered = [] {
    AddFinishedLoadingStep([] {
        blog(LOG_INFO, "[adv-ss] Android startup: automation=%s (manual only)",
             PluginIsRunning() ? "on" : "off");
        // ADB test opt-in, consumed once; normal launches never start the demo.
        const auto request = configPath("android-demo.request");
        if (QFile::exists(request) && QFile::remove(request))
            QTimer::singleShot(2500, qApp, runDemo);
    });
    AddPluginInitStep([] {
        for (const char *id : {"window", "process", "idle", "cursor", "run"})
            MacroConditionFactory::Deregister(id);
        for (const char *id : {"window", "run", "systray_notification", "source_interaction"})
            MacroActionFactory::Deregister(id);
        auto toggle = static_cast<QAction *>(obs_frontend_add_tools_menu_qaction("Director automático"));
        toggle->setCheckable(true);
        toggle->setChecked(false);
        toggle->setToolTip("Encender o apagar las macros. Siempre inicia apagado.");
        QObject::connect(toggle, &QAction::toggled, qApp, [](bool enabled) {
            if (enabled) StartPlugin(); else StopPlugin();
            obs_frontend_save();
        });
        auto sync = [toggle](bool enabled) {
            QSignalBlocker block(toggle);
            toggle->setChecked(enabled);
        };
        AddStartStep([sync] { sync(true); });
        AddStopStep([sync] { sync(false); });
        auto action = static_cast<QAction *>(obs_frontend_add_tools_menu_qaction("Probar Director automático (9 s)"));
        QObject::connect(action, &QAction::triggered, qApp, [] {
            if (QMessageBox::question(static_cast<QWidget *>(obs_frontend_get_main_window()),
                    "Prueba del Director automático",
                    "Mostrará dos escenas temporales durante 9 segundos y volverá a tu escena."
                    "\nLa automatización quedará detenida al finalizar. ¿Iniciar prueba?") == QMessageBox::Yes)
                runDemo();
        });
    });
    return true;
}();
} // namespace
} // namespace advss
