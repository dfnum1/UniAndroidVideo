using System.Collections;
using UnityEngine;

namespace GameApp.VideoTest
{
    /// <summary>
    /// Simulates an SDK initialization flow. On Android, it can launch the companion
    /// SimulatedSdkActivity so Unity experiences a real Android Activity transition.
    /// It still does not recreate an EGL context or emulate a real SDK WebView.
    /// </summary>
    public sealed class SimulatedSdkInitializer : MonoBehaviour
    {
        public enum SimulationState
        {
            Idle,
            Initializing,
            ShowingAgreement,
            LoggingIn,
            Initialized,
            Failed
        }

        [Header("Simulation")]
        [SerializeField] private bool m_AutoStart = true;
        [SerializeField] private bool m_DontDestroyOnLoad = false;
        [SerializeField] private float m_CoreInitializeDelay = 3.0f;
        [SerializeField] private float m_AgreementDelay = 0.8f;
        [SerializeField] private float m_LoginPageDelay = 8.0f;
        [SerializeField] private float m_LoginDelay = 1.0f;
        [SerializeField] private bool m_UseAndroidActivity = true;

        [Header("Optional video recovery test")]
        [Tooltip("可拖入包含 ExoMediaPlayer 的对象；这里只通过 SendMessage 调用 Pause/Play，避免依赖 Android 专用类型。")]
        [SerializeField] private GameObject m_VideoPlayerObject;
        [SerializeField] private bool m_SimulateVideoPauseResume = false;
        [SerializeField] private float m_ResumeDelay = 1.0f;

        [Header("Debug")]
        [SerializeField] private bool m_ShowOverlay = false;

        private Coroutine m_SimulationCoroutine;
        private float m_SimulationStartTime;

        public SimulationState State { get; private set; } = SimulationState.Idle;
        public bool IsInitialized => State == SimulationState.Initialized;
        public float ElapsedSeconds => State == SimulationState.Idle
            ? 0.0f
            : Time.realtimeSinceStartup - m_SimulationStartTime;

        private void Awake()
        {
            if (m_DontDestroyOnLoad)
                DontDestroyOnLoad(gameObject);
        }

        private void Start()
        {
            if (m_AutoStart)
                BeginSimulation();
        }

        /// <summary>
        /// 从外部测试脚本或按钮重新启动一次模拟 SDK 流程。
        /// </summary>
        public void BeginSimulation()
        {
            if (m_SimulationCoroutine != null)
                StopCoroutine(m_SimulationCoroutine);

            m_SimulationStartTime = Time.realtimeSinceStartup;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (m_UseAndroidActivity)
            {
                m_SimulationCoroutine = StartCoroutine(RunAndroidActivitySimulation());
                return;
            }
#endif

            m_SimulationCoroutine = StartCoroutine(RunSimulation());
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private IEnumerator RunAndroidActivitySimulation()
        {
            SetState(SimulationState.Initializing, "StartGlobalSdk / wait before simulated SDK popup");
            yield return WaitRealtime(m_LoginPageDelay);

            BeginAndroidActivitySimulation();
            m_SimulationCoroutine = null;
        }

        private void BeginAndroidActivitySimulation()
        {
            SetState(SimulationState.ShowingAgreement, "launch simulated SDK Activity / login page");

            try
            {
                using (AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (AndroidJavaObject currentActivity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (AndroidJavaClass activityClass = new AndroidJavaClass("com.unity3d.simulatedsdk.SimulatedSdkActivity"))
                {
                    activityClass.CallStatic(
                        "Launch",
                        currentActivity,
                        gameObject.name,
                        nameof(OnAndroidActivityStage),
                        0L,
                        (long)(Mathf.Max(0.0f, m_LoginDelay) * 1000.0f));
                }
            }
            catch (System.Exception exception)
            {
                SetState(SimulationState.Failed, "Unable to launch simulated SDK Activity: " + exception.Message);
                Debug.LogException(exception, this);
            }
        }

        /// <summary>
        /// Called by SimulatedSdkActivity through UnitySendMessage.
        /// </summary>
        public void OnAndroidActivityStage(string payload)
        {
            int separatorIndex = string.IsNullOrEmpty(payload) ? -1 : payload.IndexOf('|');
            string state = separatorIndex >= 0 ? payload.Substring(0, separatorIndex) : payload;
            string message = separatorIndex >= 0 ? payload.Substring(separatorIndex + 1) : payload;

            switch (state)
            {
                case "Initializing":
                    SetState(SimulationState.Initializing, message);
                    break;
                case "ShowingAgreement":
                    SetState(SimulationState.ShowingAgreement, message);
                    break;
                case "LoggingIn":
                    SetState(SimulationState.LoggingIn, message);
                    break;
                case "Initialized":
                    SetState(SimulationState.Initialized, message);
                    if (m_SimulateVideoPauseResume && m_VideoPlayerObject != null)
                        m_SimulationCoroutine = StartCoroutine(SimulateVideoPauseResume());
                    break;
                default:
                    SetState(SimulationState.Failed, message);
                    break;
            }
        }
#endif

        public void ResetSimulation()
        {
            if (m_SimulationCoroutine != null)
            {
                StopCoroutine(m_SimulationCoroutine);
                m_SimulationCoroutine = null;
            }

            State = SimulationState.Idle;
            LogStage("RESET");
        }

        private IEnumerator RunSimulation()
        {
            SetState(SimulationState.Initializing, "StartGlobalSdk");
            yield return WaitRealtime(m_CoreInitializeDelay);

            SetState(SimulationState.ShowingAgreement, "PrepareWebView / ShowAgreementSigningAgreeDialogV2");
            yield return WaitRealtime(m_AgreementDelay);

            SetState(SimulationState.LoggingIn, "Login begin");
            yield return WaitRealtime(m_LoginDelay);

            SetState(SimulationState.Initialized, "SDK initialization complete");

            if (m_SimulateVideoPauseResume && m_VideoPlayerObject != null)
                yield return SimulateVideoPauseResume();

            m_SimulationCoroutine = null;
        }

        private IEnumerator SimulateVideoPauseResume()
        {
            LogStage("TEST: call video Pause()");
            m_VideoPlayerObject.SendMessage("Pause", SendMessageOptions.DontRequireReceiver);

            yield return WaitRealtime(m_ResumeDelay);

            LogStage("TEST: call video Play()");
            m_VideoPlayerObject.SendMessage("Play", SendMessageOptions.DontRequireReceiver);
        }

        private static IEnumerator WaitRealtime(float seconds)
        {
            if (seconds > 0.0f)
                yield return new WaitForSecondsRealtime(seconds);
        }

        private void SetState(SimulationState state, string message)
        {
            State = state;
            LogStage(message);
        }

        private void LogStage(string message)
        {
            Debug.LogFormat(
                "[SimulatedSdk] state={0}, elapsed={1:0.000}s, message={2}",
                State,
                ElapsedSeconds,
                message);
        }

        private void OnGUI()
        {
            if (!m_ShowOverlay)
                return;

            const int width = 420;
            const int height = 76;
            GUI.Box(new Rect(12, 12, width, height), "Simulated SDK");
            GUI.Label(new Rect(24, 38, width - 24, 22),
                string.Format("State: {0}   Elapsed: {1:0.0}s", State, ElapsedSeconds));

            if (State == SimulationState.Initialized && GUI.Button(new Rect(width - 108, 38, 92, 24), "Run again"))
                BeginSimulation();
        }
    }
}
