
import numpy as np
import tensorflow as tf
import argparse
import os

def test_tflite_model(model_path, debug_sequence_path):
    print(f"Loading TFLite model: {model_path}")
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    # Load Debug Sequence (30, 171)
    if not os.path.exists(debug_sequence_path):
        print("Debug sequence file not found.")
        return

    seq = np.load(debug_sequence_path)
    # Reshape to [1, 30, 171]
    input_data = seq.reshape(1, 30, 171).astype(np.float32)

    # Set Input
    interpreter.set_tensor(input_details[0]['index'], input_data)
    
    # Run
    interpreter.invoke()
    
    # Get Output
    output_data = interpreter.get_tensor(output_details[0]['index'])[0]
    
    print("\n--- Android Parity Check ---")
    print("Input Tensor Sum:", np.sum(input_data))
    print("Output Logits/Probs:", output_data)
    print("Max Class Index:", np.argmax(output_data))
    print("Max Probability:", np.max(output_data))
    
    # Softmax check
    print("Sum of probs (Should be 1.0):", np.sum(output_data))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True)
    parser.add_argument('--seq', required=True)
    args = parser.parse_args()
    test_tflite_model(args.model, args.seq)
