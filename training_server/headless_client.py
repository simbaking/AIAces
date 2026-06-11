import os
import subprocess
import time
import urllib.request
import urllib.error

def run_headless():
    print("🚀 Starting Headless Java Game Client for 24/7 Training...")
    
    # 1. Ensure Maven is installed (for Colab)
    try:
        subprocess.run(["mvn", "--version"], check=True, capture_output=True)
    except Exception:
        print("📦 Maven not found. Installing Maven...")
        os.system("sudo apt-get update && sudo apt-get install -y maven")
    
    # 2. Build the project
    print("🔨 Building Java project...")
    repo_root = os.environ.get("REPO_ROOT", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    subprocess.run(["mvn", "clean", "compile"], cwd=repo_root, check=True)
    
    # 3. Start the Java app in the background
    print("☕ Starting Spring Boot Application...")
    env = os.environ.copy()
    env["TRAINING_SERVER_URL"] = "http://localhost:5001"
    
    java_proc = subprocess.Popen(
        ["mvn", "spring-boot:run"],
        cwd=repo_root,
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    
    # 4. Wait for it to start (port 8080)
    print("⏳ Waiting for Java server to start...")
    java_up = False
    for _ in range(30):
        try:
            resp = urllib.request.urlopen("http://localhost:8080/ai/training-status")
            if resp.getcode() == 200:
                java_up = True
                break
        except Exception:
            time.sleep(2)
            
    if not java_up:
        print("❌ Java server failed to start within 60 seconds.")
        java_proc.kill()
        return
        
    print("✅ Java server is UP!")
    
    # 5. Trigger Background Training
    print("🧠 Triggering self-play training...")
    try:
        req = urllib.request.Request("http://localhost:8080/ai/start-training", method="POST")
        urllib.request.urlopen(req)
        print("✅ Background training started successfully. Generating data 24/7!")
    except Exception as e:
        print(f"❌ Failed to start background training: {e}")

    # Keep script alive so Colab doesn't kill it if run directly
    try:
        java_proc.wait()
    except KeyboardInterrupt:
        print("Stopping headless client...")
        java_proc.kill()

if __name__ == "__main__":
    run_headless()
