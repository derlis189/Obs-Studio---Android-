// SPDX-License-Identifier: GPL-2.0-or-later
#include "IOSMediaProbe.hpp"

#include <QApplication>
#include <QLabel>
#include <QMainWindow>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

int main(int argc, char **argv)
{
	QApplication app(argc, argv);
	QMainWindow window;
	window.setWindowTitle(QStringLiteral("TC Studio — prueba nativa iOS"));

	auto *central = new QWidget(&window);
	auto *layout = new QVBoxLayout(central);
	auto *title = new QLabel(QStringLiteral("Captura de cámara + VideoToolbox"), central);
	auto *status = new QLabel(QStringLiteral("Preparando prueba…"), central);
	title->setAlignment(Qt::AlignCenter);
	status->setAlignment(Qt::AlignCenter);
	status->setWordWrap(true);
	title->setStyleSheet(QStringLiteral("font-size: 22px; font-weight: 600;"));
	status->setStyleSheet(QStringLiteral("font-size: 17px;"));
	layout->addWidget(title);
	layout->addWidget(status);
	window.setCentralWidget(central);

	IOSMediaProbe probe;
	QTimer refresh;
	QObject::connect(&refresh, &QTimer::timeout, &window, [&] { status->setText(probe.status()); });
	refresh.start(500);
	probe.start();
	window.resize(390, 260);
	window.show();
	const int result = app.exec();
	probe.stop();
	return result;
}
