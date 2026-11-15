// Gesher (devices track, docs/bluetooth.md): the host's Bluetooth radio.
// - The adapter, its radio, paired devices, SDP records, LE scanning and the
//   GATT client: Windows.Devices.Bluetooth (+ .Advertisement,
//   .GenericAttributeProfile), Windows.Devices.Radios and
//   Windows.Devices.Enumeration through their ABI. The interfaces are declared
//   here as the Windows metadata (Windows.Devices.winmd) lists their methods;
//   combase's WinRT functions are looked up at run time (winrt.h).
// - Classic inquiry: BluetoothApis.dll's BluetoothFindFirstDevice (loaded at
//   run time).
// - RFCOMM: Winsock's AF_BTH sockets (SDP lookup by service class UUID, SDP
//   registration of listeners with WSASetService).

#include <winsock2.h>
#include <ws2bth.h>
#include <bluetoothapis.h>

#include "bluetooth.h"

#include <string.h>

#include <map>
#include <mutex>

#include "bluetooth_queue.h"
#include "winrt.h"

namespace gesher::devices::bluetooth {

namespace {

using winrt::HSTRING;
using winrt::IAsyncOperation;
using winrt::IInspectable;
using winrt::IReference;
using winrt::Release;

// --- ABI declarations ---------------------------------------------------------------------

struct EventRegistrationToken {
  int64_t value;
};
struct DateTime {
  int64_t UniversalTime;
};
struct TimeSpan {
  int64_t Duration;
};

#define GESHER_UNUSED(n) virtual HRESULT STDMETHODCALLTYPE unused##n() = 0

// Windows.Foundation.Collections.IVectorView<T> / IVector<T>: only their first slots.
template <typename T>
struct IVectorView : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE GetAt(UINT32 index, T* item) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Size(UINT32* size) = 0;
};
template <typename T>
struct IVector : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE GetAt(UINT32 index, T* item) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Size(UINT32* size) = 0;
};

// Windows.Storage.Streams.
struct IBuffer : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Capacity(UINT32* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Length(UINT32* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE put_Length(UINT32 value) = 0;
};
struct IBufferFactory : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE Create(UINT32 capacity, IBuffer** buffer) = 0;
};
// Windows::Storage::Streams::IBufferByteAccess (robuffer.h): classic COM.
struct IBufferByteAccess : IUnknown {
  virtual HRESULT STDMETHODCALLTYPE Buffer(BYTE** value) = 0;
};

// Windows.Devices.Radios.
struct IRadio : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE SetStateAsync(int state, IAsyncOperation<int>** op) = 0;
  GESHER_UNUSED(1);  // add_StateChanged
  GESHER_UNUSED(2);  // remove_StateChanged
  virtual HRESULT STDMETHODCALLTYPE get_State(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Name(HSTRING* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Kind(int* value) = 0;
};
struct IRadioStatics : IInspectable {
  GESHER_UNUSED(1);  // GetRadiosAsync
  GESHER_UNUSED(2);  // GetDeviceSelector
  GESHER_UNUSED(3);  // FromIdAsync
  virtual HRESULT STDMETHODCALLTYPE RequestAccessAsync(IAsyncOperation<int>** op) = 0;
};

// Windows.Devices.Enumeration.
struct IDeviceInformation : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Id(HSTRING* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Name(HSTRING* value) = 0;
};
struct IDeviceInformationStatics : IInspectable {
  GESHER_UNUSED(1);  // CreateFromIdAsync
  GESHER_UNUSED(2);  // CreateFromIdAsync(id, additionalProperties)
  GESHER_UNUSED(3);  // FindAllAsync()
  GESHER_UNUSED(4);  // FindAllAsync(DeviceClass)
  virtual HRESULT STDMETHODCALLTYPE FindAllAsyncAqsFilter(
      HSTRING filter, IAsyncOperation<IVectorView<IDeviceInformation*>*>** op) = 0;
};

// Windows.Devices.Bluetooth.
struct IBluetoothClassOfDevice : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_RawValue(UINT32* value) = 0;
};
struct IRadio;
struct IBluetoothAdapter : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_DeviceId(HSTRING* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsClassicSupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsLowEnergySupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsPeripheralRoleSupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsCentralRoleSupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsAdvertisementOffloadSupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE GetRadioAsync(IAsyncOperation<IRadio*>** op) = 0;
};
struct IBluetoothAdapter3 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_IsExtendedAdvertisingSupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MaxAdvertisementDataLength(UINT32* value) = 0;
};
struct IBluetoothAdapter4 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_IsLowEnergyUncoded2MPhySupported(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsLowEnergyCodedPhySupported(uint8_t* value) = 0;
};
struct IBluetoothAdapterStatics : IInspectable {
  GESHER_UNUSED(1);  // GetDeviceSelector
  GESHER_UNUSED(2);  // FromIdAsync
  virtual HRESULT STDMETHODCALLTYPE GetDefaultAsync(IAsyncOperation<IBluetoothAdapter*>** op) = 0;
};

struct IRfcommServiceId : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
};
struct IRfcommDeviceService : IInspectable {
  GESHER_UNUSED(1);  // ConnectionHostName
  GESHER_UNUSED(2);  // ConnectionServiceName
  virtual HRESULT STDMETHODCALLTYPE get_ServiceId(IRfcommServiceId** value) = 0;
};
struct IRfcommDeviceServicesResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Error(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Services(IVectorView<IRfcommDeviceService*>** value) = 0;
};
struct IBluetoothDevice : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_DeviceId(HSTRING* value) = 0;
  GESHER_UNUSED(1);  // HostName
  virtual HRESULT STDMETHODCALLTYPE get_Name(HSTRING* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ClassOfDevice(IBluetoothClassOfDevice** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_SdpRecords(IVectorView<IBuffer*>** value) = 0;
  GESHER_UNUSED(2);  // RfcommServices
  virtual HRESULT STDMETHODCALLTYPE get_ConnectionStatus(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
};
struct IBluetoothDevice3 : IInspectable {
  GESHER_UNUSED(1);  // DeviceAccessInformation
  GESHER_UNUSED(2);  // RequestAccessAsync
  GESHER_UNUSED(3);  // GetRfcommServicesAsync()
  virtual HRESULT STDMETHODCALLTYPE GetRfcommServicesWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IRfcommDeviceServicesResult*>** op) = 0;
};
struct IBluetoothDeviceStatics : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE FromIdAsync(HSTRING id,
                                                IAsyncOperation<IBluetoothDevice*>** op) = 0;
  GESHER_UNUSED(1);  // FromHostNameAsync
  virtual HRESULT STDMETHODCALLTYPE FromBluetoothAddressAsync(
      UINT64 address, IAsyncOperation<IBluetoothDevice*>** op) = 0;
};
struct IBluetoothDeviceStatics2 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE GetDeviceSelectorFromPairingState(uint8_t paired,
                                                                      HSTRING* selector) = 0;
};

// GenericAttributeProfile.
struct IGattDescriptor;
struct IGattCharacteristic;
struct IGattDeviceService;
struct IGattReadResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Value(IBuffer** value) = 0;
};
struct IGattReadResult2 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<uint8_t>** value) = 0;
};
struct IGattWriteResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<uint8_t>** value) = 0;
};
struct IGattDescriptor : IInspectable {
  GESHER_UNUSED(1);  // get_ProtectionLevel
  GESHER_UNUSED(2);  // put_ProtectionLevel
  virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
  GESHER_UNUSED(3);  // ReadValueAsync()
  virtual HRESULT STDMETHODCALLTYPE ReadValueWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IGattReadResult*>** op) = 0;
  virtual HRESULT STDMETHODCALLTYPE WriteValueAsync(IBuffer* value,
                                                    IAsyncOperation<int>** op) = 0;
};
struct IGattDescriptor2 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE WriteValueWithResultAsync(
      IBuffer* value, IAsyncOperation<IGattWriteResult*>** op) = 0;
};
struct IGattDescriptorsResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<uint8_t>** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Descriptors(IVectorView<IGattDescriptor*>** value) = 0;
};
struct IGattCharacteristic : IInspectable {
  GESHER_UNUSED(1);  // GetDescriptors
  virtual HRESULT STDMETHODCALLTYPE get_CharacteristicProperties(UINT32* value) = 0;
  GESHER_UNUSED(2);  // get_ProtectionLevel
  GESHER_UNUSED(3);  // put_ProtectionLevel
  GESHER_UNUSED(4);  // get_UserDescription
  virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
  GESHER_UNUSED(5);  // get_PresentationFormats
  GESHER_UNUSED(6);  // ReadValueAsync()
  virtual HRESULT STDMETHODCALLTYPE ReadValueWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IGattReadResult*>** op) = 0;
  GESHER_UNUSED(7);  // WriteValueAsync(value)
  virtual HRESULT STDMETHODCALLTYPE WriteValueWithOptionAsync(IBuffer* value, int option,
                                                              IAsyncOperation<int>** op) = 0;
  GESHER_UNUSED(8);  // ReadClientCharacteristicConfigurationDescriptorAsync
  virtual HRESULT STDMETHODCALLTYPE WriteClientCharacteristicConfigurationDescriptorAsync(
      int value, IAsyncOperation<int>** op) = 0;
  virtual HRESULT STDMETHODCALLTYPE add_ValueChanged(IUnknown* handler,
                                                     EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_ValueChanged(EventRegistrationToken token) = 0;
};
struct IGattCharacteristic3 : IInspectable {
  GESHER_UNUSED(1);  // GetDescriptorsAsync()
  virtual HRESULT STDMETHODCALLTYPE GetDescriptorsWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IGattDescriptorsResult*>** op) = 0;
  GESHER_UNUSED(2);  // GetDescriptorsForUuidAsync
  GESHER_UNUSED(3);  // GetDescriptorsForUuidAsync(uuid, cacheMode)
  GESHER_UNUSED(4);  // WriteValueWithResultAsync(value)
  virtual HRESULT STDMETHODCALLTYPE WriteValueWithResultAndOptionAsync(
      IBuffer* value, int option, IAsyncOperation<IGattWriteResult*>** op) = 0;
  virtual HRESULT STDMETHODCALLTYPE WriteClientCharacteristicConfigurationDescriptorWithResultAsync(
      int value, IAsyncOperation<IGattWriteResult*>** op) = 0;
};
struct IGattCharacteristicsResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<uint8_t>** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Characteristics(
      IVectorView<IGattCharacteristic*>** value) = 0;
};
struct IGattDeviceService : IInspectable {
  GESHER_UNUSED(1);  // GetCharacteristics
  GESHER_UNUSED(2);  // GetIncludedServices
  GESHER_UNUSED(3);  // get_DeviceId
  virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
};
struct IGattDeviceService3 : IInspectable {
  GESHER_UNUSED(1);  // get_DeviceAccessInformation
  GESHER_UNUSED(2);  // get_Session
  GESHER_UNUSED(3);  // get_SharingMode
  GESHER_UNUSED(4);  // RequestAccessAsync
  GESHER_UNUSED(5);  // OpenAsync
  GESHER_UNUSED(6);  // GetCharacteristicsAsync()
  virtual HRESULT STDMETHODCALLTYPE GetCharacteristicsWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IGattCharacteristicsResult*>** op) = 0;
};
struct IGattDeviceServicesResult : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<uint8_t>** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Services(IVectorView<IGattDeviceService*>** value) = 0;
};
struct IGattValueChangedEventArgs : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_CharacteristicValue(IBuffer** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Timestamp(DateTime* value) = 0;
};
struct IGattSession : IInspectable {
  GESHER_UNUSED(1);  // get_DeviceId
  virtual HRESULT STDMETHODCALLTYPE get_CanMaintainConnection(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE put_MaintainConnection(uint8_t value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MaintainConnection(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MaxPduSize(UINT16* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_SessionStatus(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE add_MaxPduSizeChanged(IUnknown* handler,
                                                          EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_MaxPduSizeChanged(EventRegistrationToken token) = 0;
  virtual HRESULT STDMETHODCALLTYPE add_SessionStatusChanged(IUnknown* handler,
                                                             EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_SessionStatusChanged(EventRegistrationToken token) = 0;
};
struct IGattSessionStatusChangedEventArgs : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Error(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
};
struct IGattSessionStatics : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE FromDeviceIdAsync(IInspectable* deviceId,
                                                      IAsyncOperation<IGattSession*>** op) = 0;
};

struct IBluetoothLEDevice : IInspectable {
  GESHER_UNUSED(1);  // get_DeviceId
  virtual HRESULT STDMETHODCALLTYPE get_Name(HSTRING* value) = 0;
  GESHER_UNUSED(2);  // get_GattServices
  virtual HRESULT STDMETHODCALLTYPE get_ConnectionStatus(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
  GESHER_UNUSED(3);  // GetGattService
  GESHER_UNUSED(4);  // add_NameChanged
  GESHER_UNUSED(5);  // remove_NameChanged
  virtual HRESULT STDMETHODCALLTYPE add_GattServicesChanged(IUnknown* handler,
                                                            EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_GattServicesChanged(EventRegistrationToken token) = 0;
  virtual HRESULT STDMETHODCALLTYPE add_ConnectionStatusChanged(
      IUnknown* handler, EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_ConnectionStatusChanged(
      EventRegistrationToken token) = 0;
};
struct IBluetoothLEDevice2 : IInspectable {
  GESHER_UNUSED(1);  // get_DeviceInformation
  GESHER_UNUSED(2);  // get_Appearance
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddressType(int* value) = 0;
};
struct IBluetoothLEDevice3 : IInspectable {
  GESHER_UNUSED(1);  // get_DeviceAccessInformation
  GESHER_UNUSED(2);  // RequestAccessAsync
  GESHER_UNUSED(3);  // GetGattServicesAsync()
  virtual HRESULT STDMETHODCALLTYPE GetGattServicesWithCacheModeAsync(
      int cacheMode, IAsyncOperation<IGattDeviceServicesResult*>** op) = 0;
};
struct IBluetoothLEDevice4 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothDeviceId(IInspectable** value) = 0;
};
struct IBluetoothLEDeviceStatics : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE FromIdAsync(HSTRING id,
                                                IAsyncOperation<IBluetoothLEDevice*>** op) = 0;
  virtual HRESULT STDMETHODCALLTYPE FromBluetoothAddressAsync(
      UINT64 address, IAsyncOperation<IBluetoothLEDevice*>** op) = 0;
};
struct IBluetoothLEDeviceStatics2 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE GetDeviceSelectorFromPairingState(uint8_t paired,
                                                                      HSTRING* selector) = 0;
  GESHER_UNUSED(1);  // GetDeviceSelectorFromConnectionStatus
  GESHER_UNUSED(2);  // GetDeviceSelectorFromDeviceName
  GESHER_UNUSED(3);  // GetDeviceSelectorFromBluetoothAddress
  GESHER_UNUSED(4);  // GetDeviceSelectorFromBluetoothAddress(address, addressType)
  GESHER_UNUSED(5);  // GetDeviceSelectorFromAppearance
  virtual HRESULT STDMETHODCALLTYPE FromBluetoothAddressWithBluetoothAddressTypeAsync(
      UINT64 address, int addressType, IAsyncOperation<IBluetoothLEDevice*>** op) = 0;
};

// Advertisement.
struct IBluetoothLEAdvertisementDataSection : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_DataType(uint8_t* value) = 0;
  GESHER_UNUSED(1);  // put_DataType
  virtual HRESULT STDMETHODCALLTYPE get_Data(IBuffer** value) = 0;
};
struct IBluetoothLEAdvertisement : IInspectable {
  GESHER_UNUSED(1);  // get_Flags
  GESHER_UNUSED(2);  // put_Flags
  GESHER_UNUSED(3);  // get_LocalName
  GESHER_UNUSED(4);  // put_LocalName
  GESHER_UNUSED(5);  // get_ServiceUuids
  GESHER_UNUSED(6);  // get_ManufacturerData
  virtual HRESULT STDMETHODCALLTYPE get_DataSections(
      IVector<IBluetoothLEAdvertisementDataSection*>** value) = 0;
};
struct IBluetoothLEAdvertisementReceivedEventArgs : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_RawSignalStrengthInDBm(INT16* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_AdvertisementType(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Timestamp(DateTime* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Advertisement(IBluetoothLEAdvertisement** value) = 0;
};
struct IBluetoothLEAdvertisementReceivedEventArgs2 : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddressType(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_TransmitPowerLevelInDBm(IReference<INT16>** value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsAnonymous(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsConnectable(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsScannable(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsDirected(uint8_t* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_IsScanResponse(uint8_t* value) = 0;
};
struct IBluetoothLEAdvertisementWatcherStoppedEventArgs : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_Error(int* value) = 0;
};
struct IBluetoothLEAdvertisementWatcher : IInspectable {
  virtual HRESULT STDMETHODCALLTYPE get_MinSamplingInterval(TimeSpan* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MaxSamplingInterval(TimeSpan* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MinOutOfRangeTimeout(TimeSpan* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_MaxOutOfRangeTimeout(TimeSpan* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE get_ScanningMode(int* value) = 0;
  virtual HRESULT STDMETHODCALLTYPE put_ScanningMode(int value) = 0;
  GESHER_UNUSED(1);  // get_SignalStrengthFilter
  GESHER_UNUSED(2);  // put_SignalStrengthFilter
  GESHER_UNUSED(3);  // get_AdvertisementFilter
  GESHER_UNUSED(4);  // put_AdvertisementFilter
  virtual HRESULT STDMETHODCALLTYPE Start() = 0;
  virtual HRESULT STDMETHODCALLTYPE Stop() = 0;
  virtual HRESULT STDMETHODCALLTYPE add_Received(IUnknown* handler,
                                                 EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_Received(EventRegistrationToken token) = 0;
  virtual HRESULT STDMETHODCALLTYPE add_Stopped(IUnknown* handler,
                                                EventRegistrationToken* token) = 0;
  virtual HRESULT STDMETHODCALLTYPE remove_Stopped(EventRegistrationToken token) = 0;
};

#undef GESHER_UNUSED

// Interface ids, from the Windows metadata; the TypedEventHandler ones are the
// parameterized-interface ids WinRT derives (SHA-1 of their signatures).
const IID IID_IAgileObject = {0x94ea2b94, 0xe9cc, 0x49e0,
                              {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
const IID IID_IBufferFactory = {0x71af914d, 0xc10f, 0x484b,
                                {0xbc, 0x50, 0x14, 0xbc, 0x62, 0x3b, 0x3a, 0x27}};
const IID IID_IBufferByteAccess = {0x905a0fef, 0xbc53, 0x11df,
                                   {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};
const IID IID_IRadioStatics = {0x5fb6a12e, 0x67cb, 0x46ae,
                               {0xaa, 0xe9, 0x65, 0x91, 0x9f, 0x86, 0xef, 0xf4}};
const IID IID_IDeviceInformationStatics = {0xc17f100e, 0x3a46, 0x4a78,
                                           {0x80, 0x13, 0x76, 0x9d, 0xc9, 0xb9, 0x73, 0x90}};
const IID IID_IBluetoothAdapterStatics = {0x8b02fb6a, 0xac4c, 0x4741,
                                          {0x86, 0x61, 0x8e, 0xab, 0x7d, 0x17, 0xea, 0x9f}};
const IID IID_IBluetoothAdapter3 = {0x8f8624e0, 0xcba9, 0x5211,
                                    {0x9f, 0x89, 0x3a, 0xac, 0x62, 0xb4, 0xc6, 0xb8}};
const IID IID_IBluetoothAdapter4 = {0xf875f3e1, 0x6d9a, 0x5d5e,
                                    {0xae, 0xe5, 0xa1, 0x72, 0x48, 0xe5, 0xf6, 0xdd}};
const IID IID_IBluetoothDevice3 = {0x57fff78b, 0x651a, 0x4454,
                                   {0xb9, 0x0f, 0xeb, 0x21, 0xef, 0x0b, 0x0d, 0x71}};
const IID IID_IBluetoothDeviceStatics = {0x0991df51, 0x57db, 0x4725,
                                         {0xbb, 0xd7, 0x84, 0xf6, 0x43, 0x27, 0xec, 0x2c}};
const IID IID_IBluetoothDeviceStatics2 = {0xc29e8e2f, 0x4e14, 0x4477,
                                          {0xaa, 0x1b, 0xb8, 0xb4, 0x7e, 0x5b, 0x7e, 0xce}};
const IID IID_IBluetoothLEDevice2 = {0x26f062b3, 0x7aee, 0x4d31,
                                     {0xba, 0xba, 0xb1, 0xb9, 0x77, 0x5f, 0x59, 0x16}};
const IID IID_IBluetoothLEDevice3 = {0xaee9e493, 0x44ac, 0x40dc,
                                     {0xaf, 0x33, 0xb2, 0xc1, 0x3c, 0x01, 0xca, 0x46}};
const IID IID_IBluetoothLEDevice4 = {0x2b605031, 0x2248, 0x4b2f,
                                     {0xac, 0xf0, 0x7c, 0xee, 0x36, 0xfc, 0x58, 0x70}};
const IID IID_IBluetoothLEDeviceStatics = {0xc8cf1a19, 0xf0b6, 0x4bf0,
                                           {0x86, 0x89, 0x41, 0x30, 0x3d, 0xe2, 0xd9, 0xf4}};
const IID IID_IBluetoothLEDeviceStatics2 = {0x5f12c06b, 0x3bac, 0x43e8,
                                            {0xad, 0x16, 0x56, 0x32, 0x71, 0xbd, 0x41, 0xc2}};
const IID IID_IGattDeviceService3 = {0xb293a950, 0x0c53, 0x437c,
                                     {0xa9, 0xb3, 0x5c, 0x32, 0x10, 0xc6, 0xe5, 0x69}};
const IID IID_IGattCharacteristic = {0x59cb50c1, 0x5934, 0x4f68,
                                     {0xa1, 0x98, 0xeb, 0x86, 0x4f, 0xa4, 0x4e, 0x6b}};
const IID IID_IGattCharacteristic3 = {0x3f3c663e, 0x93d4, 0x406b,
                                      {0xb8, 0x17, 0xdb, 0x81, 0xf8, 0xed, 0x53, 0xb3}};
const IID IID_IGattDescriptor2 = {0x8f563d39, 0xd630, 0x406c,
                                  {0xba, 0x11, 0x10, 0xcd, 0xd1, 0x6b, 0x0e, 0x5e}};
const IID IID_IGattReadResult2 = {0xa10f50a0, 0xfb43, 0x48af,
                                  {0xba, 0xaa, 0x63, 0x8a, 0x5c, 0x63, 0x29, 0xfe}};
const IID IID_IGattValueChangedEventArgs = {0xd21bdb54, 0x06e3, 0x4ed8,
                                            {0xa2, 0x63, 0xac, 0xfa, 0xc8, 0xba, 0x73, 0x13}};
const IID IID_IGattSession = {0xd23b5143, 0xe04e, 0x4c24,
                              {0x99, 0x9c, 0x9c, 0x25, 0x6f, 0x98, 0x56, 0xb1}};
const IID IID_IGattSessionStatusChangedEventArgs = {
    0x7605b72e, 0x837f, 0x404c, {0xab, 0x34, 0x31, 0x63, 0xf3, 0x9d, 0xdf, 0x32}};
const IID IID_IGattSessionStatics = {0x2e65b95c, 0x539f, 0x4db7,
                                     {0x82, 0xa8, 0x73, 0xbd, 0xbb, 0xf7, 0x3e, 0xbf}};
const IID IID_IBluetoothLEDevice = {0xb5ee2f7b, 0x4ad8, 0x4642,
                                    {0xac, 0x48, 0x80, 0xa0, 0xb5, 0x00, 0xe8, 0x87}};
const IID IID_IBluetoothLEAdvertisementWatcher = {
    0xa6ac336f, 0xf3d3, 0x4297, {0x8d, 0x6c, 0xc8, 0x1e, 0xa6, 0x62, 0x3f, 0x40}};
const IID IID_IBluetoothLEAdvertisementReceivedEventArgs = {
    0x27987ddf, 0xe596, 0x41be, {0x8d, 0x43, 0x9e, 0x67, 0x31, 0xd4, 0xa9, 0x13}};
const IID IID_IBluetoothLEAdvertisementReceivedEventArgs2 = {
    0x12d9c87b, 0x0399, 0x5f0e, {0xa3, 0x48, 0x53, 0xb0, 0x2b, 0x6b, 0x16, 0x2e}};
const IID IID_IBluetoothLEAdvertisementWatcherStoppedEventArgs = {
    0xdd40f84d, 0xe7b9, 0x43e3, {0x9c, 0x04, 0x06, 0x85, 0xd0, 0x85, 0xfd, 0x8c}};
// TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementReceivedEventArgs>
const IID IID_ReceivedHandler = {0x90eb4eca, 0xd465, 0x5ea0,
                                 {0xa6, 0x1c, 0x03, 0x3c, 0x8c, 0x5e, 0xce, 0xf2}};
// TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementWatcherStoppedEventArgs>
const IID IID_StoppedHandler = {0x9936a4db, 0xdc99, 0x55c3,
                                {0x9e, 0x9b, 0xbf, 0x48, 0x54, 0xbd, 0x9e, 0xab}};
// TypedEventHandler<GattCharacteristic, GattValueChangedEventArgs>
const IID IID_ValueChangedHandler = {0xc1f420f6, 0x6292, 0x5760,
                                     {0xa2, 0xc9, 0x9d, 0xdf, 0x98, 0x68, 0x3c, 0xfc}};
// TypedEventHandler<GattSession, GattSessionStatusChangedEventArgs>
const IID IID_SessionStatusHandler = {0x0e1210f2, 0x7b6f, 0x543e,
                                      {0x8a, 0xdb, 0xa6, 0x1d, 0x34, 0xab, 0x53, 0x5d}};
// TypedEventHandler<GattSession, IInspectable>
const IID IID_SessionInspectableHandler = {0x6c7ec2ec, 0x9f00, 0x5ea0,
                                           {0x9a, 0x08, 0x60, 0xe5, 0x07, 0x0b, 0xcf, 0x03}};
// TypedEventHandler<BluetoothLEDevice, IInspectable>
const IID IID_LEDeviceInspectableHandler = {0xa90661e2, 0x372e, 0x5d1e,
                                            {0xbb, 0xbb, 0xb8, 0xa2, 0xce, 0x0e, 0x7c, 0x4d}};

constexpr wchar_t kAdapterClass[] = L"Windows.Devices.Bluetooth.BluetoothAdapter";
constexpr wchar_t kDeviceClass[] = L"Windows.Devices.Bluetooth.BluetoothDevice";
constexpr wchar_t kLEDeviceClass[] = L"Windows.Devices.Bluetooth.BluetoothLEDevice";
constexpr wchar_t kRadioClass[] = L"Windows.Devices.Radios.Radio";
constexpr wchar_t kDeviceInformationClass[] = L"Windows.Devices.Enumeration.DeviceInformation";
constexpr wchar_t kBufferClass[] = L"Windows.Storage.Streams.Buffer";
constexpr wchar_t kWatcherClass[] =
    L"Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher";
constexpr wchar_t kGattSessionClass[] =
    L"Windows.Devices.Bluetooth.GenericAttributeProfile.GattSession";

constexpr int kUncached = 1;  // BluetoothCacheMode (0 is Cached)
constexpr int64_t kOpTimeoutMs = 30000;

// --- helpers --------------------------------------------------------------------------------

// A delegate's vtable: TypedEventHandler<S, A> is IUnknown and Invoke.
struct IDelegate : IUnknown {
  virtual HRESULT STDMETHODCALLTYPE Invoke(IInspectable* sender, IInspectable* args) = 0;
};

// A delegate calling `f(sender, args)`.
template <typename F>
class Handler final : public IDelegate {
 public:
  Handler(const IID& iid, F f) : iid_(iid), f_(std::move(f)) {}

  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
    if (riid == IID_IUnknown || riid == iid_ || riid == IID_IAgileObject) {
      *out = static_cast<IUnknown*>(this);
      AddRef();
      return S_OK;
    }
    *out = nullptr;
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
  ULONG STDMETHODCALLTYPE Release() override {
    ULONG left = --refs_;
    if (left == 0) delete this;
    return left;
  }
  HRESULT STDMETHODCALLTYPE Invoke(IInspectable* sender, IInspectable* args) override {
    f_(sender, args);
    return S_OK;
  }

 private:
  ~Handler() = default;
  const IID iid_;
  F f_;
  std::atomic<ULONG> refs_{1};
};

template <typename F>
IUnknown* NewHandler(const IID& iid, F f) {
  return new Handler<F>(iid, std::move(f));
}

template <typename T>
HRESULT Query(IUnknown* from, const IID& iid, T** to) {
  *to = nullptr;
  if (from == nullptr) return E_POINTER;
  return from->QueryInterface(iid, reinterpret_cast<void**>(to));
}

template <typename T>
HRESULT Factory(const wchar_t* className, const IID& iid, T** out) {
  return winrt::GetActivationFactory(className, iid, reinterpret_cast<void**>(out));
}

// Waits for an IAsyncOperation<T> and takes its result; releases the operation.
template <typename T>
HRESULT Await(IAsyncOperation<T>* op, T* result, int64_t timeoutMs = kOpTimeoutMs) {
  if (op == nullptr) return E_POINTER;
  HRESULT hr = winrt::Wait(op, timeoutMs);
  if (SUCCEEDED(hr)) hr = op->GetResults(result);
  op->Release();
  return hr;
}

void CloseObject(IUnknown* object) {
  winrt::IClosable* closable = nullptr;
  if (object != nullptr && SUCCEEDED(Query(object, winrt::IID_IClosable, &closable))) {
    closable->Close();
    closable->Release();
  }
}

std::vector<uint8_t> BytesOf(IBuffer* buffer) {
  std::vector<uint8_t> out;
  if (buffer == nullptr) return out;
  UINT32 length = 0;
  IBufferByteAccess* access = nullptr;
  BYTE* data = nullptr;
  if (SUCCEEDED(buffer->get_Length(&length)) && length > 0 &&
      SUCCEEDED(Query(buffer, IID_IBufferByteAccess, &access)) &&
      SUCCEEDED(access->Buffer(&data)) && data != nullptr) {
    out.assign(data, data + length);
  }
  Release(access);
  return out;
}

HRESULT NewBuffer(const std::vector<uint8_t>& bytes, IBuffer** out) {
  *out = nullptr;
  IBufferFactory* factory = nullptr;
  HRESULT hr = Factory(kBufferClass, IID_IBufferFactory, &factory);
  if (FAILED(hr)) return hr;
  IBuffer* buffer = nullptr;
  hr = factory->Create(static_cast<UINT32>(bytes.size() > 0 ? bytes.size() : 1), &buffer);
  factory->Release();
  if (FAILED(hr)) return hr;
  IBufferByteAccess* access = nullptr;
  BYTE* data = nullptr;
  hr = Query(buffer, IID_IBufferByteAccess, &access);
  if (SUCCEEDED(hr)) hr = access->Buffer(&data);
  if (SUCCEEDED(hr) && !bytes.empty()) memcpy(data, bytes.data(), bytes.size());
  if (SUCCEEDED(hr)) hr = buffer->put_Length(static_cast<UINT32>(bytes.size()));
  Release(access);
  if (FAILED(hr)) {
    buffer->Release();
    return hr;
  }
  *out = buffer;
  return S_OK;
}

std::string TakeString(HSTRING s) {
  std::string out = Utf8(winrt::StringOf(s));
  winrt::DeleteString(s);
  return out;
}

// An Android GATT status from Windows' GattCommunicationStatus and protocol error.
int GattStatus(int communication, IReference<uint8_t>* protocolError, int deniedStatus) {
  int status = kGattFailure;
  switch (communication) {
    case 0:  // Success
      status = kGattSuccess;
      break;
    case 1:  // Unreachable
      status = kGattError;
      break;
    case 2: {  // ProtocolError: the ATT error code, as Android reports it
      uint8_t code = 0;
      if (protocolError != nullptr && SUCCEEDED(protocolError->get_Value(&code)) && code != 0) {
        status = code;
      }
      break;
    }
    case 3:  // AccessDenied
      status = deniedStatus;
      break;
  }
  if (protocolError != nullptr) protocolError->Release();
  return status;
}

// The UUIDs of the ServiceClassIDList (attribute 0x0001) of raw SDP records.
class SdpReader {
 public:
  SdpReader(const uint8_t* data, size_t size) : p_(data), end_(data + size) {}

  // One data element: its type, and its payload's extent.
  bool Element(int* type, const uint8_t** payload, size_t* length) {
    if (p_ >= end_) return false;
    uint8_t header = *p_++;
    *type = header >> 3;
    int sizeIndex = header & 7;
    size_t n = 0;
    if (*type == 0) {
      n = 0;
    } else if (sizeIndex < 5) {
      n = static_cast<size_t>(1) << sizeIndex;
    } else {
      int bytes = sizeIndex == 5 ? 1 : sizeIndex == 6 ? 2 : 4;
      if (end_ - p_ < bytes) return false;
      for (int i = 0; i < bytes; ++i) n = n << 8 | *p_++;
    }
    if (static_cast<size_t>(end_ - p_) < n) return false;
    *payload = p_;
    *length = n;
    p_ += n;
    return true;
  }

 private:
  const uint8_t* p_;
  const uint8_t* end_;
};

bool UuidOf(const uint8_t* p, size_t n, GUID* out) {
  if (n == 2) {
    *out = ShortUuid(static_cast<uint16_t>(p[0] << 8 | p[1]));
    return true;
  }
  if (n == 4) {
    *out = ShortUuid(0);
    out->Data1 = static_cast<unsigned long>(p[0]) << 24 | p[1] << 16 | p[2] << 8 | p[3];
    return true;
  }
  if (n == 16) {
    out->Data1 = static_cast<unsigned long>(p[0]) << 24 | p[1] << 16 | p[2] << 8 | p[3];
    out->Data2 = static_cast<unsigned short>(p[4] << 8 | p[5]);
    out->Data3 = static_cast<unsigned short>(p[6] << 8 | p[7]);
    memcpy(out->Data4, p + 8, 8);
    return true;
  }
  return false;
}

void AddUnique(std::vector<GUID>* list, const GUID& g) {
  for (const GUID& have : *list) {
    if (have == g) return;
  }
  list->push_back(g);
}

void ServiceClasses(const std::vector<uint8_t>& record, std::vector<GUID>* out) {
  SdpReader top(record.data(), record.size());
  int type;
  const uint8_t* body;
  size_t length;
  if (!top.Element(&type, &body, &length) || type != 6) return;  // a sequence of pairs
  SdpReader pairs(body, length);
  for (;;) {
    int idType, valueType;
    const uint8_t *id, *value;
    size_t idLength, valueLength;
    if (!pairs.Element(&idType, &id, &idLength)) break;
    if (!pairs.Element(&valueType, &value, &valueLength)) break;
    if (idType != 1 || idLength != 2 || (id[0] << 8 | id[1]) != 0x0001 || valueType != 6) continue;
    SdpReader list(value, valueLength);
    int uuidType;
    const uint8_t* uuid;
    size_t uuidLength;
    while (list.Element(&uuidType, &uuid, &uuidLength)) {
      GUID g;
      if (uuidType == 3 && UuidOf(uuid, uuidLength, &g)) AddUnique(out, g);
    }
  }
}

// --- the adapter ------------------------------------------------------------------------------

struct AdapterCache {
  std::mutex mu;
  IBluetoothAdapter* adapter = nullptr;
  IRadio* radio = nullptr;
  ULONGLONG fetchedAt = 0;
  HRESULT hr = S_OK;
};

AdapterCache& Cache() {
  static AdapterCache* cache = new AdapterCache();
  return *cache;
}

// Takes the default adapter and its radio again when the cached ones are older
// than a few seconds (a USB radio can come and go). Holds cache.mu.
void RefreshAdapterLocked(AdapterCache& cache) {
  ULONGLONG now = GetTickCount64();
  if (cache.adapter != nullptr && now - cache.fetchedAt < 5000) return;
  if (cache.adapter == nullptr && cache.fetchedAt != 0 && now - cache.fetchedAt < 2000) return;
  Release(cache.radio);
  Release(cache.adapter);
  cache.fetchedAt = now;
  IBluetoothAdapterStatics* statics = nullptr;
  cache.hr = Factory(kAdapterClass, IID_IBluetoothAdapterStatics, &statics);
  if (FAILED(cache.hr)) return;
  IAsyncOperation<IBluetoothAdapter*>* op = nullptr;
  cache.hr = statics->GetDefaultAsync(&op);
  statics->Release();
  IBluetoothAdapter* adapter = nullptr;
  if (SUCCEEDED(cache.hr)) cache.hr = Await(op, &adapter, 10000);
  if (FAILED(cache.hr)) return;
  if (adapter == nullptr) {
    cache.hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);  // no radio
    return;
  }
  cache.adapter = adapter;
  IAsyncOperation<IRadio*>* radioOp = nullptr;
  if (SUCCEEDED(adapter->GetRadioAsync(&radioOp))) {
    IRadio* radio = nullptr;
    if (SUCCEEDED(Await(radioOp, &radio, 10000))) cache.radio = radio;
  }
  cache.hr = S_OK;
}

std::string ComputerName() {
  wchar_t name[256];
  DWORD size = 256;
  if (GetComputerNameExW(ComputerNamePhysicalDnsHostname, name, &size)) {
    return Utf8(std::wstring(name, size));
  }
  return "Windows";
}

// --- BluetoothApis.dll (classic inquiry) -------------------------------------------------------

struct ClassicApi {
  HBLUETOOTH_DEVICE_FIND(WINAPI* FindFirstDevice)(const BLUETOOTH_DEVICE_SEARCH_PARAMS*,
                                                  BLUETOOTH_DEVICE_INFO*) = nullptr;
  BOOL(WINAPI* FindNextDevice)(HBLUETOOTH_DEVICE_FIND, BLUETOOTH_DEVICE_INFO*) = nullptr;
  BOOL(WINAPI* FindDeviceClose)(HBLUETOOTH_DEVICE_FIND) = nullptr;
  bool ok = false;
};

const ClassicApi& Classic() {
  static ClassicApi api;
  static std::once_flag once;
  std::call_once(once, [] {
    HMODULE dll = LoadLibraryExW(L"BluetoothApis.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (dll == nullptr) dll = LoadLibraryExW(L"bthprops.cpl", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (dll == nullptr) return;
    api.FindFirstDevice = reinterpret_cast<decltype(api.FindFirstDevice)>(
        reinterpret_cast<void*>(GetProcAddress(dll, "BluetoothFindFirstDevice")));
    api.FindNextDevice = reinterpret_cast<decltype(api.FindNextDevice)>(
        reinterpret_cast<void*>(GetProcAddress(dll, "BluetoothFindNextDevice")));
    api.FindDeviceClose = reinterpret_cast<decltype(api.FindDeviceClose)>(
        reinterpret_cast<void*>(GetProcAddress(dll, "BluetoothFindDeviceClose")));
    api.ok = api.FindFirstDevice && api.FindNextDevice && api.FindDeviceClose;
  });
  return api;
}

// --- the LE scan -------------------------------------------------------------------------------

struct ScanState {
  Queue<Advertisement> queue{2048};
  std::atomic<HRESULT> error{S_OK};
};

class WinScan final : public Scan {
 public:
  WinScan(IBluetoothLEAdvertisementWatcher* watcher, std::shared_ptr<ScanState> state,
          EventRegistrationToken received, EventRegistrationToken stopped)
      : watcher_(watcher), state_(std::move(state)), received_(received), stopped_(stopped) {}
  ~WinScan() override { Stop(); }

  bool Next(Advertisement* out, int timeoutMs) override {
    return state_->queue.Pop(out, timeoutMs);
  }
  HRESULT Error() override { return state_->error.load(); }
  void Stop() override {
    std::lock_guard<std::mutex> lock(mu_);
    if (watcher_ == nullptr) return;
    winrt::EnsureApartment();
    watcher_->Stop();
    watcher_->remove_Received(received_);
    watcher_->remove_Stopped(stopped_);
    Release(watcher_);
    state_->queue.Close();
  }

 private:
  std::mutex mu_;
  IBluetoothLEAdvertisementWatcher* watcher_;
  std::shared_ptr<ScanState> state_;
  EventRegistrationToken received_, stopped_;
};

void OnAdvertisement(ScanState* state, IInspectable* argsObject) {
  IBluetoothLEAdvertisementReceivedEventArgs* args = nullptr;
  if (FAILED(Query(argsObject, IID_IBluetoothLEAdvertisementReceivedEventArgs, &args))) return;
  Advertisement a;
  INT16 rssi = -127;
  int type = 0;
  args->get_RawSignalStrengthInDBm(&rssi);
  args->get_BluetoothAddress(&a.address);
  args->get_AdvertisementType(&type);
  a.rssi = rssi;
  a.connectable = type == 0 || type == 1;
  a.scanResponse = type == 4;
  a.legacy = type != 5;
  IBluetoothLEAdvertisementReceivedEventArgs2* args2 = nullptr;
  if (SUCCEEDED(Query(argsObject, IID_IBluetoothLEAdvertisementReceivedEventArgs2, &args2))) {
    int addressType = 0;
    if (SUCCEEDED(args2->get_BluetoothAddressType(&addressType))) {
      a.addressType = addressType == 1 ? 1 : 0;
    }
    IReference<INT16>* tx = nullptr;
    if (SUCCEEDED(args2->get_TransmitPowerLevelInDBm(&tx)) && tx != nullptr) {
      INT16 value = 127;
      if (SUCCEEDED(tx->get_Value(&value))) a.txPower = value;
      tx->Release();
    }
    uint8_t flag = 0;
    if (SUCCEEDED(args2->get_IsConnectable(&flag))) a.connectable = flag != 0;
    if (SUCCEEDED(args2->get_IsScanResponse(&flag))) a.scanResponse = flag != 0;
    args2->Release();
  }
  IBluetoothLEAdvertisement* advertisement = nullptr;
  IVector<IBluetoothLEAdvertisementDataSection*>* sections = nullptr;
  if (SUCCEEDED(args->get_Advertisement(&advertisement)) && advertisement != nullptr &&
      SUCCEEDED(advertisement->get_DataSections(&sections)) && sections != nullptr) {
    UINT32 count = 0;
    sections->get_Size(&count);
    for (UINT32 i = 0; i < count; ++i) {
      IBluetoothLEAdvertisementDataSection* section = nullptr;
      if (FAILED(sections->GetAt(i, &section)) || section == nullptr) continue;
      uint8_t dataType = 0;
      IBuffer* buffer = nullptr;
      section->get_DataType(&dataType);
      if (SUCCEEDED(section->get_Data(&buffer))) {
        std::vector<uint8_t> payload = BytesOf(buffer);
        if (payload.size() < 255) {
          a.data.push_back(static_cast<uint8_t>(payload.size() + 1));
          a.data.push_back(dataType);
          a.data.insert(a.data.end(), payload.begin(), payload.end());
        }
        Release(buffer);
      }
      section->Release();
    }
  }
  Release(sections);
  Release(advertisement);
  args->Release();
  state->queue.Push(std::move(a));
}

// --- the GATT client -------------------------------------------------------------------------

class WinGatt final : public Gatt {
 public:
  WinGatt(IBluetoothLEDevice* device) : device_(device), events_(std::make_shared<Queue<GattEvent>>(4096)) {
    std::shared_ptr<Queue<GattEvent>> events = events_;
    IUnknown* handler = NewHandler(IID_LEDeviceInspectableHandler,
                                   [events](IInspectable* sender, IInspectable*) {
      IBluetoothLEDevice* d = nullptr;
      int status = 0;
      if (SUCCEEDED(Query(sender, IID_IBluetoothLEDevice, &d))) {
        d->get_ConnectionStatus(&status);
        d->Release();
      }
      GattEvent e;
      e.type = kConnection;
      e.value = status == 1 ? 1 : 0;
      events->Push(std::move(e));
    });
    device_->add_ConnectionStatusChanged(handler, &connectionToken_);
    handler->Release();
    IUnknown* changed = NewHandler(IID_LEDeviceInspectableHandler,
                                   [events](IInspectable*, IInspectable*) {
      GattEvent e;
      e.type = kServicesChanged;
      events->Push(std::move(e));
    });
    device_->add_GattServicesChanged(changed, &servicesToken_);
    changed->Release();
  }
  ~WinGatt() override { Close(); }

  HRESULT Connect(int timeoutMs, int* mtu) override {
    winrt::EnsureApartment();
    IBluetoothLEDevice4* device4 = nullptr;
    IInspectable* deviceId = nullptr;
    {
      std::lock_guard<std::mutex> lock(mu_);
      if (device_ == nullptr) return E_ABORT;
      if (session_ == nullptr && SUCCEEDED(Query(device_, IID_IBluetoothLEDevice4, &device4))) {
        device4->get_BluetoothDeviceId(&deviceId);
        device4->Release();
      }
    }
    if (deviceId != nullptr) {
      IGattSessionStatics* statics = nullptr;
      IAsyncOperation<IGattSession*>* op = nullptr;
      IGattSession* session = nullptr;
      if (SUCCEEDED(Factory(kGattSessionClass, IID_IGattSessionStatics, &statics)) &&
          SUCCEEDED(statics->FromDeviceIdAsync(deviceId, &op)) &&
          SUCCEEDED(Await(op, &session, 10000)) && session != nullptr) {
        session->put_MaintainConnection(1);
        std::shared_ptr<Queue<GattEvent>> events = events_;
        IUnknown* status = NewHandler(IID_SessionStatusHandler,
                                      [events](IInspectable*, IInspectable* argsObject) {
          IGattSessionStatusChangedEventArgs* args = nullptr;
          int value = 0;
          if (SUCCEEDED(Query(argsObject, IID_IGattSessionStatusChangedEventArgs, &args))) {
            args->get_Status(&value);
            args->Release();
          }
          GattEvent e;
          e.type = kConnection;
          e.value = value == 1 ? 1 : 0;
          events->Push(std::move(e));
        });
        session->add_SessionStatusChanged(status, &sessionToken_);
        status->Release();
        IUnknown* pdu = NewHandler(IID_SessionInspectableHandler,
                                   [events](IInspectable* sender, IInspectable*) {
          IGattSession* s = nullptr;
          UINT16 size = 23;
          if (SUCCEEDED(Query(sender, IID_IGattSession, &s))) {
            s->get_MaxPduSize(&size);
            s->Release();
          }
          GattEvent e;
          e.type = kMtu;
          e.value = size;
          events->Push(std::move(e));
        });
        session->add_MaxPduSizeChanged(pdu, &pduToken_);
        pdu->Release();
        std::lock_guard<std::mutex> lock(mu_);
        if (device_ == nullptr) {
          session->Release();
        } else {
          session_ = session;
        }
      }
      Release(statics);
      deviceId->Release();
    }
    // With MaintainConnection Windows connects by itself; a service query makes
    // it connect now when it has not yet.
    ULONGLONG start = GetTickCount64();
    ULONGLONG deadline = start + static_cast<ULONGLONG>(timeoutMs < 0 ? 0 : timeoutMs);
    bool asked = false;
    for (;;) {
      int status = 0;
      {
        std::lock_guard<std::mutex> lock(mu_);
        if (device_ == nullptr) return E_ABORT;
        device_->get_ConnectionStatus(&status);
      }
      if (status == 1) break;
      if (GetTickCount64() >= deadline) return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
      if (!asked && GetTickCount64() >= start + 1500) {
        asked = true;
        IGattDeviceServicesResult* result = nullptr;
        if (SUCCEEDED(Services(kUncached, &result))) Release(result);
        continue;
      }
      Sleep(50);
    }
    *mtu = Mtu();
    return S_OK;
  }

  int Discover(std::vector<Attribute>* out) override {
    winrt::EnsureApartment();
    ReleaseAttributes();
    IGattDeviceServicesResult* result = nullptr;
    HRESULT hr = Services(kUncached, &result);
    if (FAILED(hr) || result == nullptr) return kGattError;
    int communication = 1;
    IReference<uint8_t>* protocolError = nullptr;
    result->get_Status(&communication);
    result->get_ProtocolError(&protocolError);
    int status = GattStatus(communication, protocolError, kGattInsufficientAuthorization);
    IVectorView<IGattDeviceService*>* services = nullptr;
    if (status == kGattSuccess) result->get_Services(&services);
    result->Release();
    if (services == nullptr) return status == kGattSuccess ? kGattFailure : status;
    UINT32 count = 0;
    services->get_Size(&count);
    for (UINT32 i = 0; i < count; ++i) {
      IGattDeviceService* service = nullptr;
      if (FAILED(services->GetAt(i, &service)) || service == nullptr) continue;
      Attribute s;
      s.kind = kService;
      service->get_Uuid(&s.uuid);
      service->get_AttributeHandle(&s.handle);
      out->push_back(s);
      AddCharacteristics(service, out);
      std::lock_guard<std::mutex> lock(mu_);
      services_.push_back(service);  // keeps it open; closed in ReleaseAttributes
    }
    services->Release();
    return kGattSuccess;
  }

  int Read(int kind, uint16_t handle, std::vector<uint8_t>* value) override {
    winrt::EnsureApartment();
    IAsyncOperation<IGattReadResult*>* op = nullptr;
    HRESULT hr = E_INVALIDARG;
    if (kind == kCharacteristic) {
      IGattCharacteristic* c = Characteristic(handle);
      if (c == nullptr) return kGattFailure;
      hr = c->ReadValueWithCacheModeAsync(kUncached, &op);
      c->Release();
    } else {
      IGattDescriptor* d = Descriptor(handle);
      if (d == nullptr) return kGattFailure;
      hr = d->ReadValueWithCacheModeAsync(kUncached, &op);
      d->Release();
    }
    IGattReadResult* result = nullptr;
    if (SUCCEEDED(hr)) hr = Await(op, &result);
    if (FAILED(hr) || result == nullptr) return kGattError;
    int communication = 1;
    result->get_Status(&communication);
    IReference<uint8_t>* protocolError = nullptr;
    IGattReadResult2* result2 = nullptr;
    if (SUCCEEDED(Query(result, IID_IGattReadResult2, &result2))) {
      result2->get_ProtocolError(&protocolError);
      result2->Release();
    }
    int status = GattStatus(communication, protocolError, kGattReadNotPermitted);
    if (status == kGattSuccess) {
      IBuffer* buffer = nullptr;
      if (SUCCEEDED(result->get_Value(&buffer))) {
        *value = BytesOf(buffer);
        Release(buffer);
      }
    }
    result->Release();
    return status;
  }

  int Write(int kind, uint16_t handle, const std::vector<uint8_t>& value,
            bool withResponse) override {
    winrt::EnsureApartment();
    IBuffer* buffer = nullptr;
    if (FAILED(NewBuffer(value, &buffer))) return kGattFailure;
    int status = kGattFailure;
    if (kind == kCharacteristic) {
      IGattCharacteristic* c = Characteristic(handle);
      if (c != nullptr) {
        status = WriteCharacteristic(c, buffer, withResponse ? 0 : 1);
        c->Release();
      }
    } else {
      IGattDescriptor* d = Descriptor(handle);
      if (d != nullptr) {
        status = WriteDescriptor(d, buffer);
        d->Release();
      }
    }
    buffer->Release();
    return status;
  }

  int SetNotify(uint16_t handle, int mode) override {
    winrt::EnsureApartment();
    IGattCharacteristic* c = Characteristic(handle);
    if (c == nullptr) return kGattFailure;
    if (mode != 0) {
      std::lock_guard<std::mutex> lock(mu_);
      if (notifyTokens_.find(handle) == notifyTokens_.end()) {
        std::shared_ptr<Queue<GattEvent>> events = events_;
        IUnknown* handler = NewHandler(IID_ValueChangedHandler,
                                       [events](IInspectable* sender, IInspectable* argsObject) {
          IGattCharacteristic* source = nullptr;
          IGattValueChangedEventArgs* args = nullptr;
          GattEvent e;
          e.type = kNotification;
          if (SUCCEEDED(Query(sender, IID_IGattCharacteristic, &source))) {
            source->get_AttributeHandle(&e.handle);
            source->Release();
          }
          if (SUCCEEDED(Query(argsObject, IID_IGattValueChangedEventArgs, &args))) {
            IBuffer* buffer = nullptr;
            if (SUCCEEDED(args->get_CharacteristicValue(&buffer))) {
              e.data = BytesOf(buffer);
              Release(buffer);
            }
            args->Release();
          }
          events->Push(std::move(e));
        });
        EventRegistrationToken token{};
        if (SUCCEEDED(c->add_ValueChanged(handler, &token))) notifyTokens_[handle] = token;
        handler->Release();
      }
    }
    int status = kGattFailure;
    IGattCharacteristic3* c3 = nullptr;
    if (SUCCEEDED(Query(c, IID_IGattCharacteristic3, &c3))) {
      IAsyncOperation<IGattWriteResult*>* op = nullptr;
      IGattWriteResult* result = nullptr;
      if (SUCCEEDED(c3->WriteClientCharacteristicConfigurationDescriptorWithResultAsync(mode, &op)) &&
          SUCCEEDED(Await(op, &result)) && result != nullptr) {
        int communication = 1;
        IReference<uint8_t>* protocolError = nullptr;
        result->get_Status(&communication);
        result->get_ProtocolError(&protocolError);
        status = GattStatus(communication, protocolError, kGattWriteNotPermitted);
        result->Release();
      } else {
        status = kGattError;
      }
      c3->Release();
    } else {
      IAsyncOperation<int>* op = nullptr;
      int communication = 1;
      if (SUCCEEDED(c->WriteClientCharacteristicConfigurationDescriptorAsync(mode, &op)) &&
          SUCCEEDED(Await(op, &communication))) {
        status = GattStatus(communication, nullptr, kGattWriteNotPermitted);
      } else {
        status = kGattError;
      }
    }
    if (mode == 0) {
      std::lock_guard<std::mutex> lock(mu_);
      auto it = notifyTokens_.find(handle);
      if (it != notifyTokens_.end()) {
        c->remove_ValueChanged(it->second);
        notifyTokens_.erase(it);
      }
    }
    c->Release();
    return status;
  }

  int Mtu() override {
    std::lock_guard<std::mutex> lock(mu_);
    UINT16 size = 23;
    if (session_ != nullptr) session_->get_MaxPduSize(&size);
    return size;
  }

  bool NextEvent(GattEvent* out, int timeoutMs) override {
    if (events_->Pop(out, timeoutMs)) return true;
    if (events_->closed()) {
      out->type = kClosed;
      return true;
    }
    return false;
  }

  void Close() override {
    winrt::EnsureApartment();
    ReleaseAttributes();
    std::lock_guard<std::mutex> lock(mu_);
    if (session_ != nullptr) {
      session_->remove_SessionStatusChanged(sessionToken_);
      session_->remove_MaxPduSizeChanged(pduToken_);
      session_->put_MaintainConnection(0);
      CloseObject(session_);
      Release(session_);
    }
    if (device_ != nullptr) {
      device_->remove_ConnectionStatusChanged(connectionToken_);
      device_->remove_GattServicesChanged(servicesToken_);
      CloseObject(device_);
      Release(device_);
    }
    events_->Close();
  }

 private:
  HRESULT Services(int cacheMode, IGattDeviceServicesResult** result) {
    *result = nullptr;
    IBluetoothLEDevice3* device3 = nullptr;
    {
      std::lock_guard<std::mutex> lock(mu_);
      if (device_ == nullptr) return E_ABORT;
      HRESULT hr = Query(device_, IID_IBluetoothLEDevice3, &device3);
      if (FAILED(hr)) return hr;
    }
    IAsyncOperation<IGattDeviceServicesResult*>* op = nullptr;
    HRESULT hr = device3->GetGattServicesWithCacheModeAsync(cacheMode, &op);
    device3->Release();
    if (SUCCEEDED(hr)) hr = Await(op, result);
    return hr;
  }

  void AddCharacteristics(IGattDeviceService* service, std::vector<Attribute>* out) {
    IGattDeviceService3* service3 = nullptr;
    if (FAILED(Query(service, IID_IGattDeviceService3, &service3))) return;
    IAsyncOperation<IGattCharacteristicsResult*>* op = nullptr;
    IGattCharacteristicsResult* result = nullptr;
    HRESULT hr = service3->GetCharacteristicsWithCacheModeAsync(kUncached, &op);
    service3->Release();
    if (SUCCEEDED(hr)) hr = Await(op, &result);
    if (FAILED(hr) || result == nullptr) return;
    int communication = 1;
    IVectorView<IGattCharacteristic*>* list = nullptr;
    result->get_Status(&communication);
    // Windows denies some services (HID) to applications: they are listed empty.
    if (communication == 0) result->get_Characteristics(&list);
    result->Release();
    if (list == nullptr) return;
    UINT32 count = 0;
    list->get_Size(&count);
    for (UINT32 i = 0; i < count; ++i) {
      IGattCharacteristic* c = nullptr;
      if (FAILED(list->GetAt(i, &c)) || c == nullptr) continue;
      Attribute a;
      a.kind = kCharacteristic;
      c->get_Uuid(&a.uuid);
      c->get_AttributeHandle(&a.handle);
      UINT32 properties = 0;
      c->get_CharacteristicProperties(&properties);
      a.properties = properties & 0xff;
      out->push_back(a);
      AddDescriptors(c, out);
      std::lock_guard<std::mutex> lock(mu_);
      auto it = chars_.find(a.handle);
      if (it != chars_.end()) it->second->Release();
      chars_[a.handle] = c;
    }
    list->Release();
  }

  void AddDescriptors(IGattCharacteristic* c, std::vector<Attribute>* out) {
    IGattCharacteristic3* c3 = nullptr;
    if (FAILED(Query(c, IID_IGattCharacteristic3, &c3))) return;
    IAsyncOperation<IGattDescriptorsResult*>* op = nullptr;
    IGattDescriptorsResult* result = nullptr;
    HRESULT hr = c3->GetDescriptorsWithCacheModeAsync(kUncached, &op);
    c3->Release();
    if (SUCCEEDED(hr)) hr = Await(op, &result);
    if (FAILED(hr) || result == nullptr) return;
    int communication = 1;
    IVectorView<IGattDescriptor*>* list = nullptr;
    result->get_Status(&communication);
    if (communication == 0) result->get_Descriptors(&list);
    result->Release();
    if (list == nullptr) return;
    UINT32 count = 0;
    list->get_Size(&count);
    for (UINT32 i = 0; i < count; ++i) {
      IGattDescriptor* d = nullptr;
      if (FAILED(list->GetAt(i, &d)) || d == nullptr) continue;
      Attribute a;
      a.kind = kDescriptor;
      d->get_Uuid(&a.uuid);
      d->get_AttributeHandle(&a.handle);
      out->push_back(a);
      std::lock_guard<std::mutex> lock(mu_);
      auto it = descs_.find(a.handle);
      if (it != descs_.end()) it->second->Release();
      descs_[a.handle] = d;
    }
    list->Release();
  }

  IGattCharacteristic* Characteristic(uint16_t handle) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = chars_.find(handle);
    if (it == chars_.end()) return nullptr;
    it->second->AddRef();
    return it->second;
  }

  IGattDescriptor* Descriptor(uint16_t handle) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = descs_.find(handle);
    if (it == descs_.end()) return nullptr;
    it->second->AddRef();
    return it->second;
  }

  int WriteCharacteristic(IGattCharacteristic* c, IBuffer* buffer, int option) {
    IGattCharacteristic3* c3 = nullptr;
    if (SUCCEEDED(Query(c, IID_IGattCharacteristic3, &c3))) {
      IAsyncOperation<IGattWriteResult*>* op = nullptr;
      IGattWriteResult* result = nullptr;
      HRESULT hr = c3->WriteValueWithResultAndOptionAsync(buffer, option, &op);
      c3->Release();
      if (SUCCEEDED(hr)) hr = Await(op, &result);
      if (FAILED(hr) || result == nullptr) return kGattError;
      int communication = 1;
      IReference<uint8_t>* protocolError = nullptr;
      result->get_Status(&communication);
      result->get_ProtocolError(&protocolError);
      result->Release();
      return GattStatus(communication, protocolError, kGattWriteNotPermitted);
    }
    IAsyncOperation<int>* op = nullptr;
    int communication = 1;
    if (FAILED(c->WriteValueWithOptionAsync(buffer, option, &op)) ||
        FAILED(Await(op, &communication))) {
      return kGattError;
    }
    return GattStatus(communication, nullptr, kGattWriteNotPermitted);
  }

  int WriteDescriptor(IGattDescriptor* d, IBuffer* buffer) {
    IGattDescriptor2* d2 = nullptr;
    if (SUCCEEDED(Query(d, IID_IGattDescriptor2, &d2))) {
      IAsyncOperation<IGattWriteResult*>* op = nullptr;
      IGattWriteResult* result = nullptr;
      HRESULT hr = d2->WriteValueWithResultAsync(buffer, &op);
      d2->Release();
      if (SUCCEEDED(hr)) hr = Await(op, &result);
      if (FAILED(hr) || result == nullptr) return kGattError;
      int communication = 1;
      IReference<uint8_t>* protocolError = nullptr;
      result->get_Status(&communication);
      result->get_ProtocolError(&protocolError);
      result->Release();
      return GattStatus(communication, protocolError, kGattWriteNotPermitted);
    }
    IAsyncOperation<int>* op = nullptr;
    int communication = 1;
    if (FAILED(d->WriteValueAsync(buffer, &op)) || FAILED(Await(op, &communication))) {
      return kGattError;
    }
    return GattStatus(communication, nullptr, kGattWriteNotPermitted);
  }

  void ReleaseAttributes() {
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& entry : notifyTokens_) {
      auto c = chars_.find(entry.first);
      if (c != chars_.end()) c->second->remove_ValueChanged(entry.second);
    }
    notifyTokens_.clear();
    for (auto& entry : descs_) entry.second->Release();
    descs_.clear();
    for (auto& entry : chars_) entry.second->Release();
    chars_.clear();
    for (IGattDeviceService* service : services_) {
      CloseObject(service);
      service->Release();
    }
    services_.clear();
  }

  std::mutex mu_;
  IBluetoothLEDevice* device_;
  IGattSession* session_ = nullptr;
  std::shared_ptr<Queue<GattEvent>> events_;
  EventRegistrationToken connectionToken_{}, servicesToken_{}, sessionToken_{}, pduToken_{};
  std::vector<IGattDeviceService*> services_;
  std::map<uint16_t, IGattCharacteristic*> chars_;
  std::map<uint16_t, IGattDescriptor*> descs_;
  std::map<uint16_t, EventRegistrationToken> notifyTokens_;
};

// --- RFCOMM over Winsock ----------------------------------------------------------------------

bool StartWinsock() {
  static bool ok = [] {
    WSADATA data;
    return WSAStartup(MAKEWORD(2, 2), &data) == 0;
  }();
  return ok;
}

// Waits until `s` is readable (or writable) or `closed` is set, in short steps
// so that Close() from another thread ends a blocked read without closing the
// handle under it. 1: ready, 0: closed, < 0: a Winsock error.
int WaitReady(SOCKET s, bool write, const std::atomic<bool>& closed, int timeoutMs = -1) {
  ULONGLONG deadline = timeoutMs < 0 ? 0 : GetTickCount64() + timeoutMs;
  while (!closed.load()) {
    fd_set set;
    FD_ZERO(&set);
    FD_SET(s, &set);
    fd_set errors;
    FD_ZERO(&errors);
    FD_SET(s, &errors);
    timeval step = {0, 200 * 1000};
    int n = select(0, write ? nullptr : &set, write ? &set : nullptr, &errors, &step);
    if (n == SOCKET_ERROR) return -WSAGetLastError();
    if (n > 0) {
      if (FD_ISSET(s, &errors)) {
        int error = 0;
        int size = sizeof(error);
        getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &size);
        return -(error != 0 ? error : WSAECONNRESET);
      }
      return 1;
    }
    if (timeoutMs >= 0 && GetTickCount64() >= deadline) return -WSAETIMEDOUT;
  }
  return 0;
}

class WinSocket final : public Socket {
 public:
  WinSocket(SOCKET s, uint64_t remote, int channel) : s_(s), remote_(remote), channel_(channel) {}
  ~WinSocket() override {
    Close();
    closesocket(s_);
  }

  int Read(uint8_t* buffer, int length) override {
    int ready = WaitReady(s_, false, closed_);
    if (ready <= 0) return ready == 0 ? 0 : ready;
    int n = recv(s_, reinterpret_cast<char*>(buffer), length, 0);
    if (n == SOCKET_ERROR) {
      int error = WSAGetLastError();
      return closed_.load() ? 0 : -error;
    }
    return n;
  }

  int Write(const uint8_t* buffer, int length) override {
    int done = 0;
    while (done < length) {
      int ready = WaitReady(s_, true, closed_);
      if (ready <= 0) return ready == 0 ? -WSAENOTCONN : ready;
      int n = send(s_, reinterpret_cast<const char*>(buffer) + done, length - done, 0);
      if (n == SOCKET_ERROR) return -WSAGetLastError();
      done += n;
    }
    return done;
  }

  int Available() override {
    u_long n = 0;
    if (closed_.load() || ioctlsocket(s_, FIONREAD, &n) != 0) return 0;
    return static_cast<int>(n);
  }

  int Channel() override { return channel_; }
  uint64_t Remote() override { return remote_; }

  void Close() override {
    if (!closed_.exchange(true)) shutdown(s_, SD_BOTH);
  }

 private:
  SOCKET s_;
  uint64_t remote_;
  int channel_;
  std::atomic<bool> closed_{false};
};

class WinServer final : public Server {
 public:
  WinServer(SOCKET s, int channel, std::wstring name, GUID uuid, SOCKADDR_BTH local)
      : s_(s), channel_(channel), name_(std::move(name)), uuid_(uuid), local_(local) {}
  ~WinServer() override {
    Close();
    closesocket(s_);
  }

  // Registers the SDP record (an RFCOMM service of `uuid` on our channel).
  bool Register(bool add) {
    CSADDR_INFO address = {};
    address.LocalAddr.lpSockaddr = reinterpret_cast<SOCKADDR*>(&local_);
    address.LocalAddr.iSockaddrLength = sizeof(local_);
    address.iSocketType = SOCK_STREAM;
    address.iProtocol = BTHPROTO_RFCOMM;
    WSAQUERYSETW set = {};
    set.dwSize = sizeof(set);
    set.lpszServiceInstanceName = const_cast<wchar_t*>(name_.c_str());
    set.lpServiceClassId = &uuid_;
    set.dwNameSpace = NS_BTH;
    set.dwNumberOfCsAddrs = 1;
    set.lpcsaBuffer = &address;
    return WSASetServiceW(&set, add ? RNRSERVICE_REGISTER : RNRSERVICE_DELETE, 0) == 0;
  }

  std::shared_ptr<Socket> Accept(int timeoutMs, HRESULT* hr) override {
    int ready = WaitReady(s_, false, closed_, timeoutMs);
    if (ready <= 0) {
      *hr = ready == 0 ? E_ABORT : HRESULT_FROM_WIN32(-ready);
      return nullptr;
    }
    SOCKADDR_BTH remote = {};
    int size = sizeof(remote);
    SOCKET c = accept(s_, reinterpret_cast<SOCKADDR*>(&remote), &size);
    if (c == INVALID_SOCKET) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      return nullptr;
    }
    *hr = S_OK;
    return std::make_shared<WinSocket>(c, remote.btAddr, channel_);
  }

  int Channel() override { return channel_; }

  void Close() override {
    if (closed_.exchange(true)) return;
    if (registered_) Register(false);
    shutdown(s_, SD_BOTH);
  }

  bool registered_ = false;

 private:
  SOCKET s_;
  int channel_;
  std::wstring name_;
  GUID uuid_;
  SOCKADDR_BTH local_;
  std::atomic<bool> closed_{false};
};

// --- the backend ---------------------------------------------------------------------------

class Windows final : public Backend {
 public:
  Adapter GetAdapter() override {
    Adapter a;
    a.via = "Windows.Devices.Bluetooth";
    AdapterCache& cache = Cache();
    std::lock_guard<std::mutex> lock(cache.mu);
    RefreshAdapterLocked(cache);
    a.hr = cache.hr;
    if (cache.adapter == nullptr) return a;
    a.present = true;
    uint8_t flag = 0;
    cache.adapter->get_BluetoothAddress(&a.address);
    if (SUCCEEDED(cache.adapter->get_IsClassicSupported(&flag))) a.classic = flag != 0;
    if (SUCCEEDED(cache.adapter->get_IsLowEnergySupported(&flag))) a.le = flag != 0;
    if (SUCCEEDED(cache.adapter->get_IsCentralRoleSupported(&flag))) a.central = flag != 0;
    if (SUCCEEDED(cache.adapter->get_IsPeripheralRoleSupported(&flag))) a.peripheral = flag != 0;
    IBluetoothAdapter3* adapter3 = nullptr;
    if (SUCCEEDED(Query(cache.adapter, IID_IBluetoothAdapter3, &adapter3))) {
      UINT32 length = 31;
      if (SUCCEEDED(adapter3->get_IsExtendedAdvertisingSupported(&flag))) {
        a.extendedAdvertising = flag != 0;
      }
      if (SUCCEEDED(adapter3->get_MaxAdvertisementDataLength(&length))) {
        a.maxAdvertisementDataLength = static_cast<int>(length);
      }
      adapter3->Release();
    }
    IBluetoothAdapter4* adapter4 = nullptr;
    if (SUCCEEDED(Query(cache.adapter, IID_IBluetoothAdapter4, &adapter4))) {
      if (SUCCEEDED(adapter4->get_IsLowEnergyUncoded2MPhySupported(&flag))) a.le2mPhy = flag != 0;
      if (SUCCEEDED(adapter4->get_IsLowEnergyCodedPhySupported(&flag))) a.leCodedPhy = flag != 0;
      adapter4->Release();
    }
    int state = 0;  // RadioState: 1 On, 2 Off, 3 Disabled
    if (cache.radio != nullptr) cache.radio->get_State(&state);
    a.on = state == 1;
    a.name = ComputerName();
    return a;
  }

  HRESULT SetEnabled(bool on) override {
    IRadioStatics* statics = nullptr;
    HRESULT hr = Factory(kRadioClass, IID_IRadioStatics, &statics);
    if (FAILED(hr)) return hr;
    IAsyncOperation<int>* access = nullptr;
    int allowed = 0;
    hr = statics->RequestAccessAsync(&access);
    statics->Release();
    if (SUCCEEDED(hr)) hr = Await(access, &allowed, 10000);
    if (FAILED(hr)) return hr;
    if (allowed != 1) return E_ACCESSDENIED;
    IRadio* radio = nullptr;
    {
      AdapterCache& cache = Cache();
      std::lock_guard<std::mutex> lock(cache.mu);
      RefreshAdapterLocked(cache);
      if (cache.radio == nullptr) return HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
      radio = cache.radio;
      radio->AddRef();
    }
    IAsyncOperation<int>* op = nullptr;
    int result = 0;  // RadioAccessStatus
    hr = radio->SetStateAsync(on ? 1 : 2, &op);
    radio->Release();
    if (SUCCEEDED(hr)) hr = Await(op, &result, 15000);
    if (FAILED(hr)) return hr;
    return result == 1 ? S_OK : E_ACCESSDENIED;
  }

  HRESULT Bonded(std::vector<Device>* out) override {
    out->clear();
    IDeviceInformationStatics* information = nullptr;
    HRESULT hr = Factory(kDeviceInformationClass, IID_IDeviceInformationStatics, &information);
    if (FAILED(hr)) return hr;
    // Classic devices paired with Windows.
    IBluetoothDeviceStatics* classic = nullptr;
    IBluetoothDeviceStatics2* classic2 = nullptr;
    if (SUCCEEDED(Factory(kDeviceClass, IID_IBluetoothDeviceStatics, &classic)) &&
        SUCCEEDED(Factory(kDeviceClass, IID_IBluetoothDeviceStatics2, &classic2))) {
      HSTRING selector = nullptr;
      if (SUCCEEDED(classic2->GetDeviceSelectorFromPairingState(1, &selector))) {
        ForEachDevice(information, selector, [&](HSTRING id) {
          IAsyncOperation<IBluetoothDevice*>* op = nullptr;
          IBluetoothDevice* device = nullptr;
          if (FAILED(classic->FromIdAsync(id, &op)) || FAILED(Await(op, &device, 10000)) ||
              device == nullptr) {
            return;
          }
          Device d;
          d.kind = kClassic;
          d.bonded = true;
          FillClassic(device, &d, false);
          device->Release();
          Merge(out, d);
        });
        winrt::DeleteString(selector);
      }
    }
    Release(classic2);
    Release(classic);
    // LE devices paired with Windows.
    IBluetoothLEDeviceStatics* le = nullptr;
    IBluetoothLEDeviceStatics2* le2 = nullptr;
    if (SUCCEEDED(Factory(kLEDeviceClass, IID_IBluetoothLEDeviceStatics, &le)) &&
        SUCCEEDED(Factory(kLEDeviceClass, IID_IBluetoothLEDeviceStatics2, &le2))) {
      HSTRING selector = nullptr;
      if (SUCCEEDED(le2->GetDeviceSelectorFromPairingState(1, &selector))) {
        ForEachDevice(information, selector, [&](HSTRING id) {
          IAsyncOperation<IBluetoothLEDevice*>* op = nullptr;
          IBluetoothLEDevice* device = nullptr;
          if (FAILED(le->FromIdAsync(id, &op)) || FAILED(Await(op, &device, 10000)) ||
              device == nullptr) {
            return;
          }
          Device d;
          d.kind = kLowEnergy;
          d.bonded = true;
          HSTRING name = nullptr;
          if (SUCCEEDED(device->get_Name(&name))) d.name = TakeString(name);
          device->get_BluetoothAddress(&d.address);
          int status = 0;
          device->get_ConnectionStatus(&status);
          d.connected = status == 1;
          IBluetoothLEDevice2* device2 = nullptr;
          if (SUCCEEDED(Query(device, IID_IBluetoothLEDevice2, &device2))) {
            int type = 0;
            if (SUCCEEDED(device2->get_BluetoothAddressType(&type))) d.addressType = type == 1;
            device2->Release();
          }
          CloseObject(device);
          device->Release();
          Merge(out, d);
        });
        winrt::DeleteString(selector);
      }
    }
    Release(le2);
    Release(le);
    information->Release();
    return S_OK;
  }

  HRESULT Inquiry(int seconds, const std::atomic<bool>* cancel,
                  const std::function<void(const Device&)>& found) override {
    const ClassicApi& api = Classic();
    if (!api.ok) return E_NOTIMPL;
    BLUETOOTH_DEVICE_SEARCH_PARAMS params = {};
    params.dwSize = sizeof(params);
    params.fReturnAuthenticated = TRUE;
    params.fReturnRemembered = TRUE;
    params.fReturnUnknown = TRUE;
    params.fReturnConnected = TRUE;
    params.fIssueInquiry = TRUE;
    // Units of 1.28 s, at most 48.
    int units = static_cast<int>(seconds / 1.28 + 0.5);
    params.cTimeoutMultiplier = static_cast<UCHAR>(units < 1 ? 1 : units > 48 ? 48 : units);
    BLUETOOTH_DEVICE_INFO info = {};
    info.dwSize = sizeof(info);
    HBLUETOOTH_DEVICE_FIND find = api.FindFirstDevice(&params, &info);
    if (find == nullptr) {
      DWORD error = GetLastError();
      return error == ERROR_NO_MORE_ITEMS ? S_OK : HRESULT_FROM_WIN32(error);
    }
    do {
      if (cancel != nullptr && cancel->load()) break;
      Device d;
      d.address = info.Address.ullLong;
      d.name = Utf8(info.szName);
      d.classOfDevice = info.ulClassofDevice;
      d.kind = kClassic;
      d.bonded = info.fAuthenticated != FALSE;
      d.connected = info.fConnected != FALSE;
      found(d);
      info = {};
      info.dwSize = sizeof(info);
    } while (api.FindNextDevice(find, &info));
    api.FindDeviceClose(find);
    return S_OK;
  }

  HRESULT RemoteUuids(uint64_t address, bool refresh, std::vector<GUID>* out) override {
    out->clear();
    IBluetoothDeviceStatics* statics = nullptr;
    HRESULT hr = Factory(kDeviceClass, IID_IBluetoothDeviceStatics, &statics);
    if (FAILED(hr)) return hr;
    IAsyncOperation<IBluetoothDevice*>* op = nullptr;
    IBluetoothDevice* device = nullptr;
    hr = statics->FromBluetoothAddressAsync(address, &op);
    statics->Release();
    if (SUCCEEDED(hr)) hr = Await(op, &device, 10000);
    if (FAILED(hr)) return hr;
    if (device == nullptr) return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
    Device d;
    FillClassic(device, &d, refresh);
    *out = d.uuids;
    device->Release();
    return S_OK;
  }

  std::shared_ptr<Scan> StartScan(bool active, HRESULT* hr) override {
    IInspectable* instance = nullptr;
    *hr = winrt::ActivateInstance(kWatcherClass, &instance);
    if (FAILED(*hr)) return nullptr;
    IBluetoothLEAdvertisementWatcher* watcher = nullptr;
    *hr = Query(instance, IID_IBluetoothLEAdvertisementWatcher, &watcher);
    instance->Release();
    if (FAILED(*hr)) return nullptr;
    watcher->put_ScanningMode(active ? 1 : 0);
    auto state = std::make_shared<ScanState>();
    std::shared_ptr<ScanState> forReceived = state;
    IUnknown* received = NewHandler(IID_ReceivedHandler,
                                    [forReceived](IInspectable*, IInspectable* args) {
      OnAdvertisement(forReceived.get(), args);
    });
    std::shared_ptr<ScanState> forStopped = state;
    IUnknown* stopped = NewHandler(IID_StoppedHandler,
                                   [forStopped](IInspectable*, IInspectable* argsObject) {
      IBluetoothLEAdvertisementWatcherStoppedEventArgs* args = nullptr;
      int error = 4;  // BluetoothError.OtherError
      if (SUCCEEDED(Query(argsObject, IID_IBluetoothLEAdvertisementWatcherStoppedEventArgs,
                          &args))) {
        args->get_Error(&error);
        args->Release();
      }
      // BluetoothError as an HRESULT (FACILITY_ITF): never S_OK, even for Success.
      forStopped->error.store(MAKE_HRESULT(SEVERITY_ERROR, FACILITY_ITF, 0x200 + error));
      forStopped->queue.Close();
    });
    EventRegistrationToken receivedToken{}, stoppedToken{};
    *hr = watcher->add_Received(received, &receivedToken);
    if (SUCCEEDED(*hr)) *hr = watcher->add_Stopped(stopped, &stoppedToken);
    received->Release();
    stopped->Release();
    if (SUCCEEDED(*hr)) *hr = watcher->Start();
    if (SUCCEEDED(*hr)) {
      int status = 0;
      watcher->get_Status(&status);
      if (status == 4) *hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_AVAILABLE);  // Aborted
    }
    if (FAILED(*hr)) {
      watcher->Stop();
      watcher->remove_Received(receivedToken);
      watcher->remove_Stopped(stoppedToken);
      watcher->Release();
      return nullptr;
    }
    return std::make_shared<WinScan>(watcher, state, receivedToken, stoppedToken);
  }

  std::shared_ptr<Gatt> OpenGatt(uint64_t address, int addressType, HRESULT* hr) override {
    IAsyncOperation<IBluetoothLEDevice*>* op = nullptr;
    IBluetoothLEDeviceStatics2* statics2 = nullptr;
    if (SUCCEEDED(Factory(kLEDeviceClass, IID_IBluetoothLEDeviceStatics2, &statics2))) {
      *hr = statics2->FromBluetoothAddressWithBluetoothAddressTypeAsync(
          address, addressType == 1 ? 1 : 0, &op);
      statics2->Release();
    } else {
      IBluetoothLEDeviceStatics* statics = nullptr;
      *hr = Factory(kLEDeviceClass, IID_IBluetoothLEDeviceStatics, &statics);
      if (SUCCEEDED(*hr)) {
        *hr = statics->FromBluetoothAddressAsync(address, &op);
        statics->Release();
      }
    }
    IBluetoothLEDevice* device = nullptr;
    if (SUCCEEDED(*hr)) *hr = Await(op, &device, 15000);
    if (FAILED(*hr)) return nullptr;
    if (device == nullptr) {
      // Windows knows no such device (neither paired nor recently seen advertising).
      *hr = HRESULT_FROM_WIN32(ERROR_DEVICE_NOT_CONNECTED);
      return nullptr;
    }
    return std::make_shared<WinGatt>(device);
  }

  std::shared_ptr<Socket> ConnectRfcomm(uint64_t address, const GUID& uuid, int channel,
                                        bool secure, HRESULT* hr) override {
    if (!StartWinsock()) {
      *hr = E_NOTIMPL;
      return nullptr;
    }
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      return nullptr;
    }
    if (secure) {
      ULONG on = TRUE;
      setsockopt(s, SOL_RFCOMM, SO_BTH_AUTHENTICATE, reinterpret_cast<char*>(&on), sizeof(on));
    }
    SOCKADDR_BTH remote = {};
    remote.addressFamily = AF_BTH;
    remote.btAddr = address;
    if (channel > 0) {
      remote.port = static_cast<ULONG>(channel);
    } else {
      remote.serviceClassId = uuid;
    }
    if (connect(s, reinterpret_cast<SOCKADDR*>(&remote), sizeof(remote)) == SOCKET_ERROR) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      closesocket(s);
      return nullptr;
    }
    SOCKADDR_BTH peer = {};
    int size = sizeof(peer);
    int port = channel;
    if (getpeername(s, reinterpret_cast<SOCKADDR*>(&peer), &size) == 0) {
      port = static_cast<int>(peer.port);
    }
    *hr = S_OK;
    return std::make_shared<WinSocket>(s, address, port);
  }

  std::shared_ptr<Server> ListenRfcomm(const std::wstring& name, const GUID& uuid, bool secure,
                                       HRESULT* hr) override {
    if (!StartWinsock()) {
      *hr = E_NOTIMPL;
      return nullptr;
    }
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      return nullptr;
    }
    if (secure) {
      ULONG on = TRUE;
      setsockopt(s, SOL_RFCOMM, SO_BTH_AUTHENTICATE, reinterpret_cast<char*>(&on), sizeof(on));
    }
    SOCKADDR_BTH local = {};
    local.addressFamily = AF_BTH;
    local.port = BT_PORT_ANY;
    int size = sizeof(local);
    if (bind(s, reinterpret_cast<SOCKADDR*>(&local), sizeof(local)) == SOCKET_ERROR ||
        listen(s, 4) == SOCKET_ERROR ||
        getsockname(s, reinterpret_cast<SOCKADDR*>(&local), &size) == SOCKET_ERROR) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      closesocket(s);
      return nullptr;
    }
    auto server = std::make_shared<WinServer>(s, static_cast<int>(local.port), name, uuid, local);
    server->registered_ = server->Register(true);
    if (!server->registered_) {
      *hr = HRESULT_FROM_WIN32(WSAGetLastError());
      return nullptr;
    }
    *hr = S_OK;
    return server;
  }

 private:
  template <typename F>
  static void ForEachDevice(IDeviceInformationStatics* information, HSTRING selector, F each) {
    IAsyncOperation<IVectorView<IDeviceInformation*>*>* op = nullptr;
    IVectorView<IDeviceInformation*>* list = nullptr;
    if (FAILED(information->FindAllAsyncAqsFilter(selector, &op)) ||
        FAILED(Await(op, &list, 15000)) || list == nullptr) {
      return;
    }
    UINT32 count = 0;
    list->get_Size(&count);
    for (UINT32 i = 0; i < count; ++i) {
      IDeviceInformation* info = nullptr;
      if (FAILED(list->GetAt(i, &info)) || info == nullptr) continue;
      HSTRING id = nullptr;
      if (SUCCEEDED(info->get_Id(&id))) {
        each(id);
        winrt::DeleteString(id);
      }
      info->Release();
    }
    list->Release();
  }

  // Name, address, class, connection and SDP service classes of a classic device.
  static void FillClassic(IBluetoothDevice* device, Device* d, bool refresh) {
    HSTRING name = nullptr;
    if (SUCCEEDED(device->get_Name(&name))) d->name = TakeString(name);
    device->get_BluetoothAddress(&d->address);
    int status = 0;
    device->get_ConnectionStatus(&status);
    d->connected = status == 1;
    IBluetoothClassOfDevice* cod = nullptr;
    if (SUCCEEDED(device->get_ClassOfDevice(&cod)) && cod != nullptr) {
      UINT32 raw = 0;
      if (SUCCEEDED(cod->get_RawValue(&raw))) d->classOfDevice = raw;
      cod->Release();
    }
    if (refresh) {
      // An uncached RFCOMM service query makes Windows read the device's SDP records again.
      IBluetoothDevice3* device3 = nullptr;
      if (SUCCEEDED(Query(device, IID_IBluetoothDevice3, &device3))) {
        IAsyncOperation<IRfcommDeviceServicesResult*>* op = nullptr;
        IRfcommDeviceServicesResult* result = nullptr;
        if (SUCCEEDED(device3->GetRfcommServicesWithCacheModeAsync(kUncached, &op)) &&
            SUCCEEDED(Await(op, &result, 20000)) && result != nullptr) {
          IVectorView<IRfcommDeviceService*>* services = nullptr;
          if (SUCCEEDED(result->get_Services(&services)) && services != nullptr) {
            UINT32 count = 0;
            services->get_Size(&count);
            for (UINT32 i = 0; i < count; ++i) {
              IRfcommDeviceService* service = nullptr;
              IRfcommServiceId* id = nullptr;
              GUID uuid;
              if (SUCCEEDED(services->GetAt(i, &service)) && service != nullptr &&
                  SUCCEEDED(service->get_ServiceId(&id)) && id != nullptr &&
                  SUCCEEDED(id->get_Uuid(&uuid))) {
                AddUnique(&d->uuids, uuid);
              }
              Release(id);
              if (service != nullptr) {
                CloseObject(service);
                service->Release();
              }
            }
            services->Release();
          }
          result->Release();
        }
        device3->Release();
      }
    }
    IVectorView<IBuffer*>* records = nullptr;
    if (SUCCEEDED(device->get_SdpRecords(&records)) && records != nullptr) {
      UINT32 count = 0;
      records->get_Size(&count);
      for (UINT32 i = 0; i < count; ++i) {
        IBuffer* record = nullptr;
        if (SUCCEEDED(records->GetAt(i, &record)) && record != nullptr) {
          ServiceClasses(BytesOf(record), &d->uuids);
          record->Release();
        }
      }
      records->Release();
    }
  }

  static void Merge(std::vector<Device>* list, const Device& d) {
    for (Device& have : *list) {
      if (have.address == d.address) {
        have.kind |= d.kind;
        have.connected = have.connected || d.connected;
        if (have.name.empty()) have.name = d.name;
        for (const GUID& g : d.uuids) AddUnique(&have.uuids, g);
        return;
      }
    }
    list->push_back(d);
  }
};

}  // namespace

Backend& WindowsBackend() {
  static Windows* backend = new Windows();
  return *backend;
}

}  // namespace gesher::devices::bluetooth
