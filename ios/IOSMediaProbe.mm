// SPDX-License-Identifier: GPL-2.0-or-later
#include "IOSMediaProbe.hpp"

#include "mobile-media-contract.h"

#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>

#include <QElapsedTimer>

#include <atomic>

@interface TCStudioProbeCapture : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate> {
@public
	std::atomic<uint64_t> framesIn;
	std::atomic<uint64_t> framesEncoded;
	std::atomic<uint32_t> frameWidth;
	std::atomic<uint32_t> frameHeight;
	std::atomic<bool> hardwareEncoder;
	std::atomic<bool> stopped;
}
@property(nonatomic, strong) AVCaptureSession *captureSession;
@property(nonatomic, strong) AVCaptureVideoDataOutput *videoOutput;
@property(nonatomic, strong) dispatch_queue_t captureQueue;
@property(nonatomic, strong) NSString *lastError;
@property(nonatomic, assign) VTCompressionSessionRef compressionSession;
- (void)start;
- (void)stop;
- (NSString *)summary;
@end

static void compression_output(void *refCon, void *, OSStatus status, VTEncodeInfoFlags,
			       CMSampleBufferRef sampleBuffer)
{
	auto *capture = (__bridge TCStudioProbeCapture *)refCon;
	if (status == noErr && sampleBuffer && CMSampleBufferDataIsReady(sampleBuffer)) {
		capture->framesEncoded.fetch_add(1, std::memory_order_relaxed);
	} else if (status != noErr) {
		capture.lastError = [NSString stringWithFormat:@"VideoToolbox falló (OSStatus %d)", (int)status];
	}
}

@implementation TCStudioProbeCapture

- (instancetype)init
{
	self = [super init];
	if (self) {
		framesIn.store(0);
		framesEncoded.store(0);
		frameWidth.store(0);
		frameHeight.store(0);
		hardwareEncoder.store(false);
		stopped.store(false);
		_captureQueue = dispatch_queue_create("org.obsandroid.port.ios-probe.capture", DISPATCH_QUEUE_SERIAL);
		_lastError = @"Esperando permiso de cámara…";
	}
	return self;
}

- (void)start
{
	AVAuthorizationStatus auth = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
	if (auth == AVAuthorizationStatusNotDetermined) {
		[AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
			if (self->stopped.load(std::memory_order_relaxed)) return;
			if (granted) dispatch_async(dispatch_get_main_queue(), ^{ [self configureCapture]; });
			else self.lastError = @"Permiso de cámara denegado.";
		}];
	} else if (auth == AVAuthorizationStatusAuthorized) {
		[self configureCapture];
	} else {
		self.lastError = @"Activa el permiso de cámara para TC Studio en Ajustes.";
	}
}

- (void)configureCapture
{
	if (stopped.load(std::memory_order_relaxed) || self.captureSession) return;
	AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
	if (!device) {
		self.lastError = @"No se encontró una cámara disponible.";
		return;
	}
	NSError *error = nil;
	AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];
	if (!input) {
		self.lastError = error.localizedDescription ?: @"No se pudo abrir la cámara.";
		return;
	}

	AVCaptureSession *session = [AVCaptureSession new];
	if ([session canSetSessionPreset:AVCaptureSessionPreset1280x720])
		session.sessionPreset = AVCaptureSessionPreset1280x720;
	AVCaptureVideoDataOutput *output = [AVCaptureVideoDataOutput new];
	output.alwaysDiscardsLateVideoFrames = YES;
	output.videoSettings = @{ (id)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) };
	if (![session canAddInput:input] || ![session canAddOutput:output]) {
		self.lastError = @"La sesión no acepta la cámara o la salida de video.";
		return;
	}
	[session beginConfiguration];
	[session addInput:input];
	[session addOutput:output];
	[session commitConfiguration];
	[output setSampleBufferDelegate:self queue:self.captureQueue];
	self.captureSession = session;
	self.videoOutput = output;
	self.lastError = @"Iniciando captura…";
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{ [session startRunning]; });
}

- (void)captureOutput:(AVCaptureOutput *)output
	didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
	fromConnection:(AVCaptureConnection *)connection
{
	CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
	if (!pixelBuffer) return;

	const uint32_t width = (uint32_t)CVPixelBufferGetWidth(pixelBuffer);
	const uint32_t height = (uint32_t)CVPixelBufferGetHeight(pixelBuffer);
	obs_mobile::VideoFrameMetadata frame{};
	frame.width = width;
	frame.height = height;
	frame.presentation_time_ns = (uint64_t)CMTimeConvertScale(CMSampleBufferGetPresentationTimeStamp(sampleBuffer), 1000000000, kCMTimeRoundingMethod_Default).value;
	frame.pixel_format = obs_mobile::VideoPixelFormat::OpaqueNative;
	frame.range = CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
		? obs_mobile::ColorRange::Full : obs_mobile::ColorRange::Limited;
	frame.rotation_degrees = connection.videoOrientation == AVCaptureVideoOrientationPortrait ? 90 : 0;
	frame.mirrored = connection.isVideoMirrored;
	framesIn.fetch_add(1, std::memory_order_relaxed);
	frameWidth.store(frame.width, std::memory_order_relaxed);
	frameHeight.store(frame.height, std::memory_order_relaxed);

	if (!self.compressionSession) {
		VTCompressionSessionRef compression = nullptr;
		NSDictionary *encoderSpecification = @{
			(__bridge NSString *)kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder : @YES
		};
		OSStatus result = VTCompressionSessionCreate(kCFAllocatorDefault, (int32_t)width, (int32_t)height,
			kCMVideoCodecType_H264, (__bridge CFDictionaryRef)encoderSpecification, nullptr, nullptr, compression_output,
			(__bridge void *)self, &compression);
		if (result != noErr || !compression) {
			self.lastError = [NSString stringWithFormat:@"No se creó VideoToolbox (OSStatus %d)", (int)result];
			return;
		}
		self.compressionSession = compression;
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFTypeRef)@30);
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_AverageBitRate, (__bridge CFTypeRef)@4000000);
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFTypeRef)@60);
		VTSessionSetProperty(compression, kVTCompressionPropertyKey_ProfileLevel,
			kVTProfileLevel_H264_Main_AutoLevel);
		VTCompressionSessionPrepareToEncodeFrames(compression);
		CFTypeRef usingHardware = nullptr;
		if (VTSessionCopyProperty(compression, kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
			kCFAllocatorDefault, &usingHardware) == noErr && usingHardware)
			hardwareEncoder.store(CFBooleanGetValue((CFBooleanRef)usingHardware), std::memory_order_relaxed);
		if (usingHardware) CFRelease(usingHardware);
	}

	VTEncodeInfoFlags flags = 0;
	OSStatus result = VTCompressionSessionEncodeFrame(self.compressionSession, pixelBuffer,
		CMSampleBufferGetPresentationTimeStamp(sampleBuffer), CMSampleBufferGetDuration(sampleBuffer),
		nullptr, nullptr, &flags);
	if (result != noErr)
		self.lastError = [NSString stringWithFormat:@"No se codificó un cuadro (OSStatus %d)", (int)result];
	else
		self.lastError = @"Captura y codificación activas.";
}

- (void)stop
{
	stopped.store(true, std::memory_order_relaxed);
	AVCaptureSession *session = self.captureSession;
	if (session) {
		[session stopRunning];
		[self.videoOutput setSampleBufferDelegate:nil queue:nullptr];
		dispatch_sync(self.captureQueue, ^{});
		self.videoOutput = nil;
		self.captureSession = nil;
	}
	if (self.compressionSession) {
		VTCompressionSessionCompleteFrames(self.compressionSession, kCMTimeInvalid);
		VTCompressionSessionInvalidate(self.compressionSession);
		CFRelease(self.compressionSession);
		self.compressionSession = nullptr;
	}
}

- (NSString *)summary
{
	return [NSString stringWithFormat:@"%@\n\nEntrada: %llu cuadros\nVideoToolbox: %llu cuadros H.264\nTamaño: %u × %u\nEncoder hardware: %@",
		self.lastError ?: @"", framesIn.load(std::memory_order_relaxed),
		framesEncoded.load(std::memory_order_relaxed), frameWidth.load(std::memory_order_relaxed),
		frameHeight.load(std::memory_order_relaxed), hardwareEncoder.load(std::memory_order_relaxed) ? @"sí" : @"no / no informado"];
}

@end

struct IOSMediaProbe::Impl {
	__strong TCStudioProbeCapture *capture = nil;
	QElapsedTimer rateTimer;
	uint64_t lastInputFrames = 0;
	uint64_t lastEncodedFrames = 0;
	double inputFps = 0.0;
	double encodedFps = 0.0;
};

IOSMediaProbe::IOSMediaProbe() : impl(new Impl)
{
	impl->capture = [TCStudioProbeCapture new];
}

IOSMediaProbe::~IOSMediaProbe()
{
	stop();
	delete impl;
}

void IOSMediaProbe::start()
{
	[impl->capture start];
}

void IOSMediaProbe::stop()
{
	if (impl && impl->capture) [impl->capture stop];
}

QString IOSMediaProbe::status()
{
	const uint64_t input = impl->capture->framesIn.load(std::memory_order_relaxed);
	const uint64_t encoded = impl->capture->framesEncoded.load(std::memory_order_relaxed);
	if (!impl->rateTimer.isValid()) {
		impl->rateTimer.start();
		impl->lastInputFrames = input;
		impl->lastEncodedFrames = encoded;
	} else if (const qint64 elapsed = impl->rateTimer.restart(); elapsed > 0) {
		impl->inputFps = double(input - impl->lastInputFrames) * 1000.0 / double(elapsed);
		impl->encodedFps = double(encoded - impl->lastEncodedFrames) * 1000.0 / double(elapsed);
		impl->lastInputFrames = input;
		impl->lastEncodedFrames = encoded;
	}
	return QString::fromUtf8(impl->capture.summary.UTF8String ?: "")
		+ QStringLiteral("\nFPS (captura / H.264): %1 / %2")
			.arg(impl->inputFps, 0, 'f', 1).arg(impl->encodedFps, 0, 'f', 1);
}
