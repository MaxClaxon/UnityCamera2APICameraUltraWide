using UnityEngine;
using UnityEngine.UI;
using UnityEngine.Android;
using System.Collections.Generic;
using TMPro;

public class Camera2PluginController : MonoBehaviour
{
    [Header("Output")]
    public RawImage targetRawImage;
    public Material cameraMaterial;

    [Header("Camera Selection")]
    public  TMP_Dropdown cameraDropdown;

    [Header("Image Tuning (Sliders)")]
    public Slider sliderGamma;      // Min: 0.1, Max: 3.0
    public Slider sliderExposure;   // Min: 0.0, Max: 5.0
    public Slider sliderContrast;   // Min: 0.0, Max: 2.0
    public Slider sliderSaturation; // Min: 0.0, Max: 2.0

    [Header("Settings")]
    [SerializeField] private int _width = 1280;
    [SerializeField] private int _height = 720;

    private AndroidJavaObject _cameraPlugin;
    private Texture2D _nativeTexture;
    private List<string> _availableCameraIds = new List<string>();

    void Start()
    {
        // 1. Настраиваем слайдеры на "хорошие" значения по умолчанию
        // Это гарантия того, что экран не будет черным при запуске
        if (sliderGamma != null) sliderGamma.value = 0.8f;
        if (sliderExposure != null) sliderExposure.value = 1.5f;
        if (sliderContrast != null) sliderContrast.value = 1.05f;
        if (sliderSaturation != null) sliderSaturation.value = 1.2f;

        if (!Permission.HasUserAuthorizedPermission(Permission.Camera))
        {
            Permission.RequestUserPermission(Permission.Camera);
        }

        if (Application.platform == RuntimePlatform.Android)
        {
            InitializeCameraPlugin();
        }
    }

    void InitializeCameraPlugin()
    {
        AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        AndroidJavaObject activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        AndroidJavaObject context = activity.Call<AndroidJavaObject>("getApplicationContext");

        _cameraPlugin = new AndroidJavaObject("ru.maxonclaxon.camera2apiplugin.Camera2Wrapper", context);

        PopulateCameraDropdown();

        // Запускаем первую камеру
        if (_availableCameraIds.Count > 0)
        {
            StartCameraById(_availableCameraIds[0]);
        }
    }

    void PopulateCameraDropdown()
    {
        if (cameraDropdown == null) return;
        cameraDropdown.ClearOptions();
        _availableCameraIds.Clear();

        string cameraListStr = _cameraPlugin.Call<string>("getCameraListInfo");
        if (string.IsNullOrEmpty(cameraListStr)) return;

        List<string> options = new List<string>();
        string[] cameras = cameraListStr.Split('|');

        foreach (string camInfo in cameras)
        {
            if (string.IsNullOrEmpty(camInfo)) continue;
            string[] parts = camInfo.Split(':');
            if (parts.Length >= 3)
            {
                string id = parts[0];
                string facing = parts[1];
                string focal = parts[2];
                _availableCameraIds.Add(id);
                options.Add($"ID {id} [{facing}] f={focal}");
            }
        }
        cameraDropdown.AddOptions(options);
        cameraDropdown.onValueChanged.AddListener(OnCameraChanged);
    }

    public void OnCameraChanged(int index)
    {
        if (index >= 0 && index < _availableCameraIds.Count)
        {
            StartCameraById(_availableCameraIds[index]);
        }
    }

    void StartCameraById(string id)
    {
        if (_cameraPlugin == null) return;

        // Запуск Java камеры
        int textureId = _cameraPlugin.Call<int>("startCamera", id, _width, _height);

        if (textureId <= 0) return;

        if (_nativeTexture != null) Destroy(_nativeTexture);

        _nativeTexture = Texture2D.CreateExternalTexture(_width, _height, TextureFormat.RGBA32, false, false, (System.IntPtr)textureId);
        _nativeTexture.wrapMode = TextureWrapMode.Clamp;
        _nativeTexture.filterMode = FilterMode.Bilinear;

        if (targetRawImage != null && cameraMaterial != null)
        {
            targetRawImage.texture = _nativeTexture;
            targetRawImage.material = cameraMaterial;

            // Сразу применяем настройки при старте новой камеры
            ApplyShaderSettings();
        }
    }

    void Update()
    {
        if (_cameraPlugin != null)
        {
            _cameraPlugin.Call("updateTexture");
        }

        // Каждый кадр обновляем настройки шейдера из слайдеров
        // Это позволяет крутить их в реальном времени
        ApplyShaderSettings();
    }

    void ApplyShaderSettings()
    {
        if (cameraMaterial == null) return;

        // Если слайдеры назначены - берем значения с них
        if (sliderGamma != null) cameraMaterial.SetFloat("_Gamma", sliderGamma.value);
        if (sliderExposure != null) cameraMaterial.SetFloat("_Exposure", sliderExposure.value);
        if (sliderContrast != null) cameraMaterial.SetFloat("_Contrast", sliderContrast.value);
        if (sliderSaturation != null) cameraMaterial.SetFloat("_Saturation", sliderSaturation.value);
    }

    void OnDestroy()
    {
        if (_cameraPlugin != null)
        {
            _cameraPlugin.Call("stopCamera");
            _cameraPlugin = null;
        }
    }

    void OnApplicationPause(bool pauseStatus)
    {
        if (_cameraPlugin == null) return;
        if (pauseStatus)
        {
            _cameraPlugin.Call("stopCamera");
        }
        else
        {
            Invoke(nameof(RestartCameraDelayed), 0.5f);
        }
    }

    void RestartCameraDelayed()
    {
        if (cameraDropdown != null && _availableCameraIds.Count > 0)
        {
            StartCameraById(_availableCameraIds[cameraDropdown.value]);
        }
    }
}